package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class PaymentConfirmOutcomeTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundScenarioSeeder scenarioSeeder;
    @MockBean private TossPaymentsClient tossPaymentsClient;

    private final Request request = Request.create(HttpMethod.POST, "/v1/payments/confirm",
            Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    @Test
    @DisplayName("읽기 타임아웃이면 PENDING으로 남긴다")
    void readTimeout_keepsPending() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        when(tossPaymentsClient.confirm(any(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.PENDING);
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
    }

    @Test
    @DisplayName("회로가 열려 요청이 안 나갔으면 READY로 되돌린다")
    void circuitOpen_revertsToReady() {
        RefundScenarioSeeder.ConfirmScenario scenario = readyPayment();
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("toss-payment");
        when(tossPaymentsClient.confirm(any(), anyString()))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(breaker));

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);

        assertThat(statusOf(scenario)).isEqualTo(PaymentStatus.READY);
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

    private RetryableException readTimeout() {
        return new RetryableException(-1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }
}
