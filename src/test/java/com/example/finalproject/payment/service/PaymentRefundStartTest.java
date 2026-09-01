package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentRefundStartTest extends IntegrationTestSupport {

    @Autowired
    private PaymentCommandService paymentCommandService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;

    @Test
    @DisplayName("환불 행이 없으면 만들면서 PG_PENDING 으로 시작한다")
    void startRefund_createsRefundRowInPgPending() {
        RefundTarget target = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());

        paymentCommandService.startRefund(target);

        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();
        PaymentRefund refund = paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_REQUESTED);
        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.PG_PENDING);
        assertThat(refund.getRefundAmount()).isEqualTo(target.amount());
    }

    @Test
    @DisplayName("환불 행이 이미 있으면 PG_PENDING 으로 전이한다")
    void startRefund_movesExistingRequestedRowToPgPending() {
        RefundTarget target = refundScenarioSeeder.refundRequested(newBuyerEmail());

        paymentCommandService.startRefund(target);

        PaymentRefund refund = paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow();
        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("이미 REFUND_REQUESTED 인 결제에 다시 시작하지 않는다")
    void startRefund_whenAlreadyRefundRequested_throws() {
        RefundTarget target = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());
        paymentCommandService.startRefund(target);

        assertThatThrownBy(() -> paymentCommandService.startRefund(target))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Payment 와 PaymentRefund 가 같은 트랜잭션에서 커밋된다")
    void startRefund_commitsBothOrNeither() {
        RefundTarget target = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());
        RefundTarget broken = new RefundTarget(target.orderId(), -1L, target.amount(), target.reason());

        assertThatThrownBy(() -> paymentCommandService.startRefund(broken))
                .isInstanceOf(BusinessException.class);

        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();
        assertThat(payment.getPaymentStatus())
                .as("PaymentRefund 저장이 실패하면 Payment 상태도 남으면 안 된다")
                .isEqualTo(PaymentStatus.APPROVED);
    }

    private String newBuyerEmail() {
        return "refund-start-buyer-" + System.nanoTime() + "@test.com";
    }
}
