package com.example.finalproject.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.enums.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionPaymentTest {

    @Test
    void markReversalPending_fromPending() {
        SubscriptionPayment payment = paymentWith(PaymentStatus.PENDING);

        payment.markReversalPending();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.REVERSAL_PENDING);
    }

    @Test
    void markReversalPending_fromApproved_throws() {
        SubscriptionPayment payment = paymentWith(PaymentStatus.APPROVED);

        assertThatThrownBy(payment::markReversalPending).isInstanceOf(BusinessException.class);
    }

    @Test
    void markReconciliationRequired_hasNoGuard() {
        SubscriptionPayment payment = paymentWith(PaymentStatus.REVERSAL_PENDING);

        payment.markReconciliationRequired();

        assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    private SubscriptionPayment paymentWith(PaymentStatus status) {
        SubscriptionPayment payment = SubscriptionPayment.builder().build();
        ReflectionTestUtils.setField(payment, "paymentStatus", status);
        return payment;
    }
}
