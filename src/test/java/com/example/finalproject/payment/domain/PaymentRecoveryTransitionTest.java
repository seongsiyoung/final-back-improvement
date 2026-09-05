package com.example.finalproject.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentRecoveryTransitionTest {

    private Payment paymentWith(PaymentStatus status, Integer refundedAmount, int amount) {
        Payment payment = new Payment();
        ReflectionTestUtils.setField(payment, "paymentStatus", status);
        ReflectionTestUtils.setField(payment, "refundedAmount", refundedAmount);
        ReflectionTestUtils.setField(payment, "amount", amount);
        return payment;
    }

    @Test
    @DisplayName("PENDING 에서만 REVERSAL_PENDING 으로 간다")
    void markReversalPending_fromPending() {
        Payment payment = paymentWith(PaymentStatus.PENDING, 0, 10000);

        payment.markReversalPending();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REVERSAL_PENDING);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("PENDING 이 아니면 REVERSAL_PENDING 으로 못 간다")
    void markReversalPending_fromOthers(PaymentStatus status) {
        Payment payment = paymentWith(status, 0, 10000);

        assertThatThrownBy(payment::markReversalPending)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("환불액이 0이면 REFUND_REQUESTED 를 APPROVED 로 되돌린다")
    void revertRefundRequest_withNoRefund_goesToApproved() {
        Payment payment = paymentWith(PaymentStatus.REFUND_REQUESTED, 0, 10000);

        payment.revertRefundRequest();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("환불액이 null 이어도 APPROVED 로 되돌린다")
    void revertRefundRequest_withNullRefund_goesToApproved() {
        Payment payment = paymentWith(PaymentStatus.REFUND_REQUESTED, null, 10000);

        payment.revertRefundRequest();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("부분 환불액이 남아 있으면 PARTIAL_REFUNDED 로 되돌린다")
    void revertRefundRequest_withPartialRefund_goesToPartialRefunded() {
        Payment payment = paymentWith(PaymentStatus.REFUND_REQUESTED, 3000, 10000);

        payment.revertRefundRequest();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(payment.getRefundedAmount()).isEqualTo(3000);
    }

    @Test
    @DisplayName("REFUND_REQUESTED 가 아니면 되돌리지 않는다")
    void revertRefundRequest_fromApproved_throws() {
        Payment payment = paymentWith(PaymentStatus.APPROVED, 0, 10000);

        assertThatThrownBy(payment::revertRefundRequest)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("RECONCILIATION_REQUIRED 는 어느 상태에서든 기록할 수 있다")
    void markReconciliationRequired_hasNoGuard() {
        Payment payment = paymentWith(PaymentStatus.REVERSAL_PENDING, 0, 10000);

        payment.markReconciliationRequired();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }
}
