package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import feign.RetryableException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentReversalOutcomeTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundScenarioSeeder scenarioSeeder;
    @Autowired private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @MockBean private TossPaymentsClient tossPaymentsClient;

    private final Request request = Request.create(HttpMethod.POST, "/v1/payments/x/cancel",
            Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    @BeforeEach
    void resetCircuitBreaker() {
        ((Resilience4JCircuitBreakerFactory) circuitBreakerFactory).getCircuitBreakerRegistry()
                .circuitBreaker("toss-payment").reset();
    }

    @Test
    @DisplayName("보상 취소가 성공하면 FAILED가 된다")
    void reversalSucceeds_marksFailed() {
        RefundScenarioSeeder.ConfirmScenario scenario = outOfStockPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenReturn(mock());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("보상 취소 결과를 모르면 REVERSAL_PENDING으로 남긴다")
    void reversalResultUnknown_keepsReversalPending() {
        RefundScenarioSeeder.ConfirmScenario scenario = outOfStockPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.REVERSAL_PENDING);
    }

    @Test
    @DisplayName("PG가 보상 취소를 거절하면 RECONCILIATION_REQUIRED가 된다")
    void reversalRejected_marksReconciliationRequired() {
        RefundScenarioSeeder.ConfirmScenario scenario = outOfStockPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenThrow(new FeignException.BadRequest(
                "bad request", request, "{\"code\":\"NOT_CANCELABLE_PAYMENT\"}".getBytes(StandardCharsets.UTF_8),
                Collections.emptyMap()));

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("REVERSAL_PENDING은 취소 호출 전에 커밋된다")
    void reversalPendingIsCommittedBeforeCancelCall() {
        RefundScenarioSeeder.ConfirmScenario scenario = outOfStockPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenAnswer(invocation -> {
            assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.REVERSAL_PENDING);
            throw new RuntimeException("cancel call interrupted");
        });

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);
    }

    private RefundScenarioSeeder.ConfirmScenario outOfStockPayment() {
        return scenarioSeeder.outOfStockPayment("reversal-" + System.nanoTime() + "@test.com");
    }

    private TossConfirmResponse doneResponse() {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "status", "DONE");
        ReflectionTestUtils.setField(response, "paymentKey", "pg-payment-key");
        return response;
    }

    private PaymentStatus statusOf(RefundScenarioSeeder.ConfirmScenario scenario) {
        return paymentRepository.findById(scenario.paymentId()).orElseThrow().getPaymentStatus();
    }

    private RetryableException readTimeout() {
        return new RetryableException(-1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }
}
