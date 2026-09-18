package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.request.TossCancelRequest;
import com.example.finalproject.payment.dto.response.TossCancelResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** 미전송 보상 취소의 재전송 경로를 검증한다. */
class ReversalResendTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentReconciliationService paymentReconciliationService;
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
    @DisplayName("조회 결과가 DONE 이면 취소가 안 나간 것이므로 다시 보낸다")
    void whenPgStillDone_resendsCancel() {
        Long paymentId = seedReversalPending();
        int amount = reload(paymentId).getAmount();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString()))
                .thenReturn(mock(TossCancelResponse.class));

        paymentReconciliationService.reconcile(reload(paymentId));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<TossCancelRequest> bodyCaptor = ArgumentCaptor.forClass(TossCancelRequest.class);
        verify(tossPaymentsClient).cancel(anyString(), bodyCaptor.capture(), keyCaptor.capture());

        assertThat(keyCaptor.getValue())
                .as("원 요청과 같은 키여야 인플라이트 취소와 재전송이 서로를 안다")
                .isEqualTo("compensate-" + paymentId);
        assertThat(bodyCaptor.getValue().getCancelAmount()).isEqualTo(amount);
        assertThat(statusOf(paymentId))
                .as("재전송이 성공하면 종결된다")
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("이미 취소된 결제에는 다시 보내지 않는다")
    void whenPgAlreadyCanceled_doesNotResend() {
        Long paymentId = seedReversalPending();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(statusResponse("CANCELED"));

        paymentReconciliationService.reconcile(reload(paymentId));

        verify(tossPaymentsClient, never()).cancel(anyString(), any(), anyString());
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    @DisplayName("재전송 결과를 모르면 상태를 두고 다음 주기에 다시 시도한다")
    void whenResendResultUnknown_keepsReversalPending() {
        Long paymentId = seedReversalPending();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenThrow(readTimeout());

        paymentReconciliationService.reconcile(reload(paymentId));

        assertThat(statusOf(paymentId))
                .as("모르면 건드리지 않는다. 다음 주기가 다시 집는다")
                .isEqualTo(PaymentStatus.REVERSAL_PENDING);

        Mockito.doReturn(mock(TossCancelResponse.class))
                .when(tossPaymentsClient).cancel(anyString(), any(), anyString());
        paymentReconciliationService.reconcile(reload(paymentId));

        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.FAILED);
        verify(tossPaymentsClient, times(2)).cancel(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("PG가 재전송을 명확히 거절하면 확인 필요로 올린다")
    void whenResendRejected_marksReconciliationRequired() {
        Long paymentId = seedReversalPending();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenThrow(
                new FeignException.BadRequest("bad request", request,
                        "{\"code\":\"NOT_CANCELABLE_PAYMENT\"}".getBytes(StandardCharsets.UTF_8),
                        Collections.emptyMap()));

        paymentReconciliationService.reconcile(reload(paymentId));

        assertThat(statusOf(paymentId))
                .as("자동으로 할 수 있는 일이 없으므로 사람에게 넘긴다")
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("재전송이 계속 실패하면 시도 횟수가 올라 관리자 화면에 잡힌다")
    void whenResendKeepsFailing_countsAttempt() {
        Long paymentId = seedReversalPending();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenThrow(readTimeout());

        paymentReconciliationService.reconcile(reload(paymentId));

        assertThat(reload(paymentId).getReconcileAttempts())
                .as("세지 않으면 계속 실패하는 건이 자동 복구 정체 집계에 영영 안 잡힌다")
                .isEqualTo(1);

        paymentReconciliationService.reconcile(reload(paymentId));

        assertThat(reload(paymentId).getReconcileAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("PG 상태가 아직 종결되지 않았으면 다시 보내지 않는다")
    void whenPgStatusNotTerminal_doesNotResend() {
        Long paymentId = seedReversalPending();
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(statusResponse("IN_PROGRESS"));

        paymentReconciliationService.reconcile(reload(paymentId));

        verify(tossPaymentsClient, never()).cancel(anyString(), any(), anyString());
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.REVERSAL_PENDING);
    }

    private Long seedReversalPending() {
        RefundScenarioSeeder.ConfirmScenario scenario =
                scenarioSeeder.outOfStockPayment("resend-" + System.nanoTime() + "@test.com");
        when(tossPaymentsClient.confirm(any(), anyString())).thenReturn(doneResponse());
        when(tossPaymentsClient.cancel(anyString(), any(), anyString())).thenThrow(readTimeout());

        assertThatThrownBy(() -> paymentService.confirm(scenario.email(), scenario.request()))
                .isInstanceOf(RuntimeException.class);
        assertThat(statusOf(scenario.paymentId())).isEqualTo(PaymentStatus.REVERSAL_PENDING);

        Mockito.reset(tossPaymentsClient);
        return scenario.paymentId();
    }

    private Payment reload(Long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow();
    }

    private TossConfirmResponse doneResponse() {
        return statusResponse("DONE");
    }

    private TossConfirmResponse statusResponse(String status) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "status", status);
        ReflectionTestUtils.setField(response, "paymentKey", "pg-payment-key");
        return response;
    }

    private PaymentStatus statusOf(Long paymentId) {
        return paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus();
    }

    private RetryableException readTimeout() {
        return new RetryableException(-1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }
}
