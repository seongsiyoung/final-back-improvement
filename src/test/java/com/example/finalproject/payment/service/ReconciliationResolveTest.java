package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ReconciliationResolveTest extends IntegrationTestSupport {

    @Autowired private AdminRefundCommandService adminRefundCommandService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository refundRepository;
    @Autowired private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PaymentCommandService paymentCommandService;

    @Test
    @DisplayName("환불이 확인되면 장부에 반영하고 활성 건에서 뺀다")
    void resolveRefund_asRefunded_appliesLedger() {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());

        adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.REFUNDED, target.amount());

        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.APPROVED);
        assertThat(paymentStatusOf(target)).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundRepository.findActiveByStoreOrderId(target.storeOrderId())).isEmpty();
    }

    @Test
    @DisplayName("취소가 없다고 해제한 뒤에는 그 주문에 다시 환불을 요청할 수 있다")
    void resolveRefund_unblocksFutureRefund() {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());

        adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.NOT_REFUNDED, null);

        assertThatCode(() -> paymentCommandService.startRefund(target))
                .doesNotThrowAnyException();
        assertThat(refundRepository.findActiveByStoreOrderId(target.storeOrderId()))
                .isPresent()
                .get()
                .extracting(PaymentRefund::getRefundStatus)
                .isEqualTo(RefundStatus.PG_PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = StoreOrderStatus.class,
            names = {"CANCEL_REQUESTED", "REJECT_REQUESTED", "REFUND_REQUESTED"})
    @DisplayName("취소가 없다고 확인한 환불은 요청 갈래에 맞게 주문 상태를 복원한다")
    void resolveRefund_asNotRefunded_restoresStoreOrderByRequestType(StoreOrderStatus requestedStatus) {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(
                newBuyerEmail(), requestedStatus);

        adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.NOT_REFUNDED, null);

        assertThat(storeOrderStatusOf(target)).isEqualTo(expectedRestoredStatus(requestedStatus));
        assertThat(paymentStatusOf(target)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.PG_REJECTED);
    }

    @Test
    @DisplayName("확인 필요가 아닌 환불은 해제 대상이 아니다")
    void resolveRefund_rejectsNonReconciliationTarget() {
        RefundTarget target = refundScenarioSeeder.refundRequested(newBuyerEmail());

        assertThatThrownBy(() -> adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.NOT_REFUNDED, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("REFUNDED로 해제할 때 금액이 없으면 거부한다")
    void resolveRefund_requiresAmountWhenRefunded() {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());

        assertThatThrownBy(() -> adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.REFUNDED, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("확인된 누적 취소액은 결제에만 반영하고 현재 환불 이력 금액은 보존한다")
    void resolveRefund_keepsRefundAttemptAmountWhenConfirmedAmountIsCumulative() {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());
        Long refundId = activeRefundIdOf(target);
        Long paymentId = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getId();
        jdbcTemplate.update("update payments set refunded_amount = ? where id = ?", 1000, paymentId);
        jdbcTemplate.update("update payment_refunds set refund_amount = ? where id = ?", 2000, refundId);

        adminRefundCommandService.resolveReconciliation(refundId, ReconciliationOutcome.REFUNDED, 3000);

        assertThat(refundRepository.findById(refundId).orElseThrow().getRefundAmount()).isEqualTo(2000);
        assertThat(paymentStatusOf(target)).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("확인된 누적 취소액은 이미 기록된 취소액보다 작을 수 없다")
    void resolveRefund_rejectsConfirmedAmountLowerThanRecordedAmount() {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());
        Long paymentId = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getId();
        jdbcTemplate.update("update payments set refunded_amount = ? where id = ?", 2000, paymentId);

        assertThatThrownBy(() -> adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.REFUNDED, 1000))
                .isInstanceOf(BusinessException.class);

        assertThat(paymentStatusOf(target)).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
    }

    private Long activeRefundIdOf(RefundTarget target) {
        return refundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow().getId();
    }

    private Long activeRefundIdOfClosedHistory(RefundTarget target) {
        return refundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()).stream()
                .filter(refund -> refund.getRefundStatus() == RefundStatus.PG_REJECTED)
                .findFirst().orElseThrow().getId();
    }

    private PaymentStatus paymentStatusOf(RefundTarget target) {
        return paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus();
    }

    private RefundStatus latestRefundStatus(RefundTarget target) {
        return refundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()).stream()
                .findFirst().map(PaymentRefund::getRefundStatus).orElseThrow();
    }

    private StoreOrderStatus storeOrderStatusOf(RefundTarget target) {
        return jdbcTemplate.queryForObject(
                "select status from store_orders where id = ?", StoreOrderStatus.class, target.storeOrderId());
    }

    private StoreOrderStatus expectedRestoredStatus(StoreOrderStatus requestedStatus) {
        return switch (requestedStatus) {
            case CANCEL_REQUESTED -> StoreOrderStatus.PENDING;
            case REJECT_REQUESTED -> StoreOrderStatus.REJECT_REQUESTED;
            case REFUND_REQUESTED -> StoreOrderStatus.DELIVERED;
            default -> throw new IllegalArgumentException("unsupported requested status: " + requestedStatus);
        };
    }

    private String newBuyerEmail() {
        return "reconciliation-resolve-" + System.nanoTime() + "@test.com";
    }
}
