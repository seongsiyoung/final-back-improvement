package com.example.finalproject.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SubscriptionScenarioSeeder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 스케줄러 주기를 기다리지 않고 메서드를 직접 부른다. 통합 테스트가 DB 를 공유하므로
 * 각 테스트는 먼저 다른 테스트가 남긴 구독 결제를 시간 경계 밖으로 밀어낸 뒤 자기 것을 만든다.
 */
class SubscriptionReconciliationSchedulerTest extends IntegrationTestSupport {

    @Autowired
    private SubscriptionReconciliationScheduler subscriptionReconciliationScheduler;
    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;
    @Autowired
    private SubscriptionScenarioSeeder subscriptionScenarioSeeder;
    @MockBean
    private TossPaymentsClient tossPaymentsClient;

    @Test
    @DisplayName("스케줄러는 PG 호출을 트랜잭션 밖에서 한다")
    void reconcile_callsPgOutsideTransaction() {
        SubscriptionPayment payment = onlyStuck(PaymentStatus.PENDING);
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return done();
        });

        subscriptionReconciliationScheduler.reconcileStuckSubscriptionPayments();

        assertThat(transactionActive).isFalse();
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지를 계속 처리한다")
    void reconcile_continuesAfterOneFailure() {
        subscriptionScenarioSeeder.hideAllSubscriptionReconciliationTargets();
        SubscriptionPayment failing = stuck(PaymentStatus.PENDING);
        SubscriptionPayment healthy = stuck(PaymentStatus.PENDING);
        when(tossPaymentsClient.getPaymentByOrderId(eq(failing.getPgOrderId())))
                .thenThrow(new RuntimeException("Toss 조회 실패"));
        when(tossPaymentsClient.getPaymentByOrderId(eq(healthy.getPgOrderId()))).thenReturn(done());

        subscriptionReconciliationScheduler.reconcileStuckSubscriptionPayments();

        assertThat(statusOf(healthy)).isEqualTo(PaymentStatus.APPROVED);
        assertThat(statusOf(failing))
                .as("실패한 건은 상태가 남아 다음 주기에 다시 잡힌다")
                .isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("최근에 상태가 바뀐 건은 같은 주기에 다시 집지 않는다")
    void reconcile_ignoresRecentTargets() {
        subscriptionScenarioSeeder.hideAllSubscriptionReconciliationTargets();
        SubscriptionPayment payment = subscriptionScenarioSeeder.stuckSubscriptionPayment(
                email(), PaymentStatus.PENDING, 0);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(done());

        subscriptionReconciliationScheduler.reconcileStuckSubscriptionPayments();

        verify(tossPaymentsClient, never()).getPaymentByOrderId(anyString());
        assertThat(statusOf(payment)).isEqualTo(PaymentStatus.PENDING);
    }

    private SubscriptionPayment onlyStuck(PaymentStatus status) {
        subscriptionScenarioSeeder.hideAllSubscriptionReconciliationTargets();
        return stuck(status);
    }

    private SubscriptionPayment stuck(PaymentStatus status) {
        return subscriptionScenarioSeeder.stuckSubscriptionPayment(email(), status, 30);
    }

    private PaymentStatus statusOf(SubscriptionPayment payment) {
        return subscriptionPaymentRepository.findById(payment.getId()).orElseThrow().getPaymentStatus();
    }

    private TossConfirmResponse done() {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "paymentKey", "sub-sched-key-" + System.nanoTime());
        ReflectionTestUtils.setField(response, "status", "DONE");
        return response;
    }

    private String email() {
        return "sub-sched-" + System.nanoTime() + "@test.com";
    }
}
