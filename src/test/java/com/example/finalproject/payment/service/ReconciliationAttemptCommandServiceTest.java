package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import com.example.finalproject.testsupport.SubscriptionScenarioSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReconciliationAttemptCommandServiceTest extends IntegrationTestSupport {

    @Autowired private ReconciliationAttemptCommandService reconciliationAttemptCommandService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private SubscriptionPaymentRepository subscriptionPaymentRepository;
    @Autowired private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired private SubscriptionScenarioSeeder subscriptionScenarioSeeder;

    @Test
    void queryFailure_recordsPaymentAttemptTimeWithoutIncreasingAttempts() {
        Long paymentId = refundScenarioSeeder.stuckPayment(
                "attempt-payment-" + System.nanoTime() + "@test.com", PaymentStatus.PENDING, 30);

        reconciliationAttemptCommandService.recordPaymentAttempt(paymentId, false);

        var payment = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(payment.getLastReconciledAt()).isNotNull();
        assertThat(payment.getReconcileAttempts()).isZero();
    }

    @Test
    void unresolvedSuccessfulLookup_increasesRefundAndSubscriptionAttempts() {
        RefundTarget refundTarget = refundScenarioSeeder.stuckInPgPending(
                "attempt-refund-" + System.nanoTime() + "@test.com");
        Long refundId = paymentRefundRepository.findActiveByStoreOrderId(refundTarget.storeOrderId()).orElseThrow().getId();
        Long subscriptionPaymentId = subscriptionScenarioSeeder.stuckSubscriptionPayment(
                "attempt-subscription-" + System.nanoTime() + "@test.com", PaymentStatus.PENDING, 30).getId();

        reconciliationAttemptCommandService.recordRefundAttempt(refundId, true);
        reconciliationAttemptCommandService.recordSubscriptionPaymentAttempt(subscriptionPaymentId, true);

        assertThat(paymentRefundRepository.findById(refundId).orElseThrow().getReconcileAttempts()).isEqualTo(1);
        assertThat(subscriptionPaymentRepository.findById(subscriptionPaymentId).orElseThrow().getReconcileAttempts())
                .isEqualTo(1);
    }
}
