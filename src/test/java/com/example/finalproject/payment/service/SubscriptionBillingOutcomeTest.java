package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.SubscriptionScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import feign.RetryableException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionBillingOutcomeTest extends IntegrationTestSupport {

    @Autowired private SubscriptionBillingService subscriptionBillingService;
    @Autowired private SubscriptionPaymentRepository subscriptionPaymentRepository;
    @Autowired private SubscriptionScenarioSeeder scenarioSeeder;
    @Autowired private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @MockBean private TossPaymentsClient tossPaymentsClient;
    @MockBean private PaymentGateWay paymentGateWay;

    private final Request request = Request.create(HttpMethod.POST, "/v1/billing/x",
            Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    @BeforeEach
    void resetCircuitBreaker() {
        ((Resilience4JCircuitBreakerFactory) circuitBreakerFactory).getCircuitBreakerRegistry()
                .circuitBreaker("toss-billing").reset();
    }

    @Test
    @DisplayName("승인 결과를 모르면 PENDING으로 남긴다")
    void approveResultUnknown_keepsPending() {
        Subscription subscription = activeSubscription();
        when(tossPaymentsClient.approveBilling(anyString(), any(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> subscriptionBillingService.chargeMonthlyFee(subscription.getId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(latestPaymentStatus(subscription)).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("카드사가 거절하면 FAILED가 된다")
    void approveRejected_marksFailed() {
        Subscription subscription = activeSubscription();
        when(tossPaymentsClient.approveBilling(anyString(), any(), anyString())).thenThrow(cardRejected());

        assertThatThrownBy(() -> subscriptionBillingService.chargeMonthlyFee(subscription.getId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(latestPaymentStatus(subscription)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("PENDING으로 남으면 같은 주기에 다시 청구하지 않는다")
    void pendingCharge_blocksSameCycleRetry() {
        Subscription subscription = activeSubscription();
        when(tossPaymentsClient.approveBilling(anyString(), any(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> subscriptionBillingService.chargeMonthlyFee(subscription.getId()))
                .isInstanceOf(RuntimeException.class);
        long countAfterFirst = subscriptionPaymentRepository.count();

        assertThatThrownBy(() -> subscriptionBillingService.chargeMonthlyFee(subscription.getId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(subscriptionPaymentRepository.count()).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("보상 취소 결과를 모르면 REVERSAL_PENDING으로 남긴다")
    void reversalResultUnknown_keepsReversalPending() {
        Subscription subscription = activeSubscription();
        when(tossPaymentsClient.approveBilling(anyString(), any(), anyString())).thenReturn(responseWithoutCard());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> subscriptionBillingService.chargeMonthlyFee(subscription.getId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(latestPaymentStatus(subscription)).isEqualTo(PaymentStatus.REVERSAL_PENDING);
        verify(paymentGateWay).cancel(anyString(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("PG가 보상 취소를 거절하면 RECONCILIATION_REQUIRED가 된다")
    void reversalRejected_marksReconciliationRequired() {
        Subscription subscription = activeSubscription();
        when(tossPaymentsClient.approveBilling(anyString(), any(), anyString())).thenReturn(responseWithoutCard());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString())).thenThrow(cardRejected());

        assertThatThrownBy(() -> subscriptionBillingService.chargeMonthlyFee(subscription.getId()))
                .isInstanceOf(RuntimeException.class);

        assertThat(latestPaymentStatus(subscription)).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        verify(paymentGateWay).cancel(anyString(), anyInt(), anyString(), anyString());
    }

    private Subscription activeSubscription() {
        return scenarioSeeder.active("sub-outcome-" + System.nanoTime() + "@test.com");
    }

    private PaymentStatus latestPaymentStatus(Subscription subscription) {
        return subscriptionPaymentRepository.findAll().stream()
                .filter(payment -> payment.getSubscription().getId().equals(subscription.getId()))
                .max(java.util.Comparator.comparing(payment -> payment.getId()))
                .orElseThrow()
                .getPaymentStatus();
    }

    private TossBillingApproveResponse responseWithoutCard() {
        TossBillingApproveResponse response = new TossBillingApproveResponse();
        ReflectionTestUtils.setField(response, "paymentKey", "billing-payment-key");
        return response;
    }

    private RetryableException readTimeout() {
        return new RetryableException(-1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }

    private FeignException cardRejected() {
        return new FeignException.BadRequest("bad request", request,
                "{\"code\":\"REJECT_CARD_COMPANY\"}".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
    }
}
