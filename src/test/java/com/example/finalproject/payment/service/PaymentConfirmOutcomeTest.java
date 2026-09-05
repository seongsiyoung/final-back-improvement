package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
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
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentConfirmOutcomeTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundScenarioSeeder scenarioSeeder;
    @Autowired private CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    @MockBean private TossPaymentsClient tossPaymentsClient;

    private final Request request = Request.create(HttpMethod.POST, "/v1/payments/confirm",
            Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    @BeforeEach
    void resetCircuitBreaker() {
        ((Resilience4JCircuitBreakerFactory) circuitBreakerFactory).getCircuitBreakerRegistry()
                .circuitBreaker("toss-payment").reset();
    }

    @Test
    @DisplayName("읽기 타임아웃 뒤 PG 조회가 DONE이면 기존 완료 경로로 승인한다")
    void readTimeout_thenPgLookupDone_completesPayment() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());
        when(tossPaymentsClient.getPaymentByOrderId(pgOrderId(scenario))).thenReturn(responseWithStatus("DONE"));

        paymentService.confirm(scenario.email(), scenario.request());

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.APPROVED);
        verify(tossPaymentsClient, times(1)).getPaymentByOrderId(pgOrderId(scenario));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "CANCELED", "EXPIRED"})
    @DisplayName("읽기 타임아웃 뒤 PG가 종결 실패면 FAILED로 확정한다")
    void readTimeout_thenPgLookupTerminalFailure_failsPayment(String pgStatus) {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());
        when(tossPaymentsClient.getPaymentByOrderId(pgOrderId(scenario))).thenReturn(responseWithStatus(pgStatus));

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.FAILED);
        verify(tossPaymentsClient, times(1)).getPaymentByOrderId(pgOrderId(scenario));
    }

    @Test
    @DisplayName("읽기 타임아웃 뒤 PG에 결제 기록이 없으면 FAILED로 확정한다")
    void readTimeout_thenPgLookupNotFound_failsPayment() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());
        when(tossPaymentsClient.getPaymentByOrderId(pgOrderId(scenario))).thenThrow(notFound());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.FAILED);
        verify(tossPaymentsClient, times(1)).getPaymentByOrderId(pgOrderId(scenario));
    }

    @Test
    @DisplayName("읽기 타임아웃 뒤 PG가 진행 중이면 PENDING으로 남긴다")
    void readTimeout_thenPgLookupInProgress_keepsPending() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());
        when(tossPaymentsClient.getPaymentByOrderId(pgOrderId(scenario))).thenReturn(responseWithStatus("IN_PROGRESS"));

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.PENDING);
        verify(tossPaymentsClient, times(1)).getPaymentByOrderId(pgOrderId(scenario));
    }

    @Test
    @DisplayName("읽기 타임아웃 뒤 PG가 부분 취소면 재조정 필요 상태로 남긴다")
    void readTimeout_thenPgLookupPartialCanceled_marksReconciliationRequired() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());
        when(tossPaymentsClient.getPaymentByOrderId(pgOrderId(scenario)))
                .thenReturn(responseWithStatus("PARTIAL_CANCELED"));

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        verify(tossPaymentsClient, times(1)).getPaymentByOrderId(pgOrderId(scenario));
    }

    @Test
    @DisplayName("읽기 타임아웃 뒤 PG 재조회도 실패하면 PENDING으로 남긴다")
    void readTimeout_thenPgLookupFails_keepsPending() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());
        when(tossPaymentsClient.getPaymentByOrderId(pgOrderId(scenario))).thenThrow(readTimeout());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.PENDING);
        verify(tossPaymentsClient, times(1)).getPaymentByOrderId(pgOrderId(scenario));
    }

    @Test
    @DisplayName("PG가 명확히 거절하면 READY로 되돌린다")
    void explicitRejection_revertsToReady() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(new FeignException.BadRequest(
                "bad request", request, "{\"code\":\"REJECT_CARD_COMPANY\"}".getBytes(StandardCharsets.UTF_8),
                Collections.emptyMap()));

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.READY);
        verify(tossPaymentsClient, never()).getPaymentByOrderId(anyString());
    }

    @Test
    @DisplayName("회로가 열려 요청이 안 나갔으면 READY로 되돌린다")
    void circuitOpen_revertsToReady() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        CircuitBreaker breaker = ((Resilience4JCircuitBreakerFactory) circuitBreakerFactory)
                .getCircuitBreakerRegistry()
                .circuitBreaker("toss-payment");
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.READY);
        verify(tossPaymentsClient, never()).confirm(any(), anyString());
        verify(tossPaymentsClient, never()).getPaymentByOrderId(anyString());
    }

    @Test
    @DisplayName("PENDING으로 남은 결제는 다시 승인 요청할 수 없다")
    void pendingPayment_cannotBeConfirmedAgain() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(BusinessException.class);
    }

    private RefundScenarioSeeder.ConfirmScenario readyPayment() {
        return scenarioSeeder.readyPayment("confirm-" + System.nanoTime() + "@test.com");
    }

    private PaymentStatus statusOf(RefundScenarioSeeder.ConfirmScenario scenario) {
        return paymentRepository.findById(scenario.paymentId()).orElseThrow().getPaymentStatus();
    }

    private String pgOrderId(RefundScenarioSeeder.ConfirmScenario scenario) {
        return paymentRepository.findById(scenario.paymentId()).orElseThrow().getPgOrderId();
    }

    private RetryableException readTimeout() {
        return new RetryableException(-1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }

    private FeignException.NotFound notFound() {
        return new FeignException.NotFound("not found", request, null, null);
    }

    private TossConfirmResponse responseWithStatus(String status) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "status", status);
        ReflectionTestUtils.setField(response, "paymentKey", "test-payment-key");
        return response;
    }
}
