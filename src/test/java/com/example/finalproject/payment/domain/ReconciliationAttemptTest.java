package com.example.finalproject.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import org.junit.jupiter.api.Test;

class ReconciliationAttemptTest {

    @Test
    void payment_queryFailure_recordsAttemptTimeWithoutIncreasingAttempts() {
        Payment payment = Payment.builder()
                .paymentStatus(PaymentStatus.PENDING)
                .amount(10_000)
                .pgOrderId("payment-order")
                .build();

        payment.recordReconciliationAttempt(false);

        assertThat(payment.getLastReconciledAt()).isNotNull();
        assertThat(payment.getReconcileAttempts()).isZero();
    }

    @Test
    void refund_unresolvedSuccessfulQuery_increasesAttempts() {
        PaymentRefund refund = PaymentRefund.builder()
                .refundStatus(RefundStatus.PG_PENDING)
                .refundAmount(1_000)
                .build();

        refund.recordReconciliationAttempt(true);

        assertThat(refund.getLastReconciledAt()).isNotNull();
        assertThat(refund.getReconcileAttempts()).isEqualTo(1);
    }

    @Test
    void subscriptionPayment_unresolvedSuccessfulQuery_increasesAttempts() {
        SubscriptionPayment payment = SubscriptionPayment.builder()
                .paymentStatus(PaymentStatus.REVERSAL_PENDING)
                .amount(10_000)
                .pgOrderId("subscription-order")
                .build();

        payment.recordReconciliationAttempt(true);

        assertThat(payment.getLastReconciledAt()).isNotNull();
        assertThat(payment.getReconcileAttempts()).isEqualTo(1);
    }
}
