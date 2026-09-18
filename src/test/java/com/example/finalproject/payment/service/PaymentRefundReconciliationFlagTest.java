package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import com.example.finalproject.testsupport.RefundScenarioSeeder.MultiStoreScenario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentRefundReconciliationFlagTest extends IntegrationTestSupport {

    @Autowired
    private PaymentCommandService paymentCommandService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;

    @Test
    @DisplayName("아직 확정되지 않은 환불은 결제와 환불 행 모두 확인 필요로 표시한다")
    void marksBothWhenRefundIsUnsettled() {
        RefundTarget target = refundScenarioSeeder.stuckInPgPending(newBuyerEmail());

        paymentCommandService.markRefundReconciliationRequired(target);

        assertThat(paymentStatusOf(target)).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(latestRefundStatusOf(target)).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("이미 환불이 확정된 결제는 확인 필요로 덮지 않는다")
    void doesNotOverwriteSettledPayment() {
        RefundTarget target = refundScenarioSeeder.stuckInPgApproved(newBuyerEmail());
        paymentCommandService.applyRefund(
                target.orderId(), target.storeOrderId(), target.amount(), target.reason(), target.amount());

        paymentCommandService.markRefundReconciliationRequired(target);

        assertThat(paymentStatusOf(target))
                .as("정상 종결된 환불이 장애 건으로 뒤집히면 이후 환불까지 막힌다")
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(latestRefundStatusOf(target)).isEqualTo(RefundStatus.APPROVED);
    }

    @Test
    @DisplayName("다른 매장 환불로 결제가 부분 환불이 되어도 이 매장 환불 행은 확인 필요로 표시한다")
    void marksRefundRowEvenWhenPaymentAlreadyPartiallyRefunded() {
        MultiStoreScenario scenario = refundScenarioSeeder.twoStoresOneOrder(newBuyerEmail());
        RefundTarget storeB = scenario.storeB();
        refundScenarioSeeder.stuckRefundOn(storeB, RefundStatus.PG_APPROVED);
        refundScenarioSeeder.forcePartiallyRefundedPayment(scenario.orderId());

        paymentCommandService.markRefundReconciliationRequired(storeB);

        assertThat(paymentStatusOf(storeB))
                .as("결제는 주문 단위라 덮어쓰면 남은 환불까지 막힌다")
                .isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(latestRefundStatusOf(storeB))
                .as("환불 행은 매장 단위다. 표시하지 않으면 관리자 목록에 뜨지 않고 스캔이 5분마다 같은 실패를 반복한다")
                .isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
    }

    private PaymentStatus paymentStatusOf(RefundTarget target) {
        return paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus();
    }

    private RefundStatus latestRefundStatusOf(RefundTarget target) {
        return paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId())
                .stream().findFirst().map(PaymentRefund::getRefundStatus).orElseThrow();
    }

    private String newBuyerEmail() {
        return "refund-flag-" + System.nanoTime() + "@test.com";
    }
}
