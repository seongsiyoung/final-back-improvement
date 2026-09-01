package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentReconciliationServiceTest {

    private TossPaymentsClient tossPaymentsClient;
    private PaymentConfirmCommandService paymentConfirmCommandService;
    private PaymentReconciliationService paymentReconciliationService;

    @BeforeEach
    void setUp() {
        tossPaymentsClient = mock(TossPaymentsClient.class);
        paymentConfirmCommandService = mock(PaymentConfirmCommandService.class);
        paymentReconciliationService = new PaymentReconciliationService(
                tossPaymentsClient, paymentConfirmCommandService);
    }

    private Payment pendingPayment(Long id, String pgOrderId) {
        Payment payment = Payment.builder()
                .paymentStatus(PaymentStatus.PENDING)
                .amount(10000)
                .pgOrderId(pgOrderId)
                .pgProvider("tosspayments")
                .build();
        ReflectionTestUtils.setField(payment, "id", id);
        return payment;
    }

    private Payment reversalPendingPayment(Long id, String pgOrderId) {
        Payment payment = pendingPayment(id, pgOrderId);
        ReflectionTestUtils.setField(payment, "paymentStatus", PaymentStatus.REVERSAL_PENDING);
        return payment;
    }

    private TossConfirmResponse responseWithStatus(String status) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "status", status);
        ReflectionTestUtils.setField(response, "paymentKey", "test-payment-key");
        return response;
    }

    @Test
    void reconcile_whenPgStatusDone_callsCompleteConfirm() {
        Payment payment = pendingPayment(1L, "order-1");
        TossConfirmResponse pg = responseWithStatus("DONE");
        when(tossPaymentsClient.getPaymentByOrderId("order-1")).thenReturn(pg);

        paymentReconciliationService.reconcile(payment);

        verify(paymentConfirmCommandService).completeConfirm(1L, "test-payment-key", pg);
        verify(paymentConfirmCommandService, never()).failPending(any());
    }

    @Test
    void reconcile_whenPgStatusNotDone_callsFailPending() {
        Payment payment = pendingPayment(2L, "order-2");
        TossConfirmResponse pg = responseWithStatus("ABORTED");
        when(tossPaymentsClient.getPaymentByOrderId("order-2")).thenReturn(pg);

        paymentReconciliationService.reconcile(payment);

        verify(paymentConfirmCommandService).failPending(2L);
        verify(paymentConfirmCommandService, never()).completeConfirm(any(), any(), any());
    }

    @Test
    void reconcile_whenPgHasNoRecord_callsFailPending() {
        Payment payment = pendingPayment(3L, "order-3");
        Request request = Request.create(HttpMethod.GET, "/v1/payments/orders/order-3",
                Collections.emptyMap(), null, StandardCharsets.UTF_8, new RequestTemplate());
        when(tossPaymentsClient.getPaymentByOrderId("order-3"))
                .thenThrow(new FeignException.NotFound("not found", request, null, null));

        paymentReconciliationService.reconcile(payment);

        verify(paymentConfirmCommandService).failPending(3L);
        verify(paymentConfirmCommandService, never()).completeConfirm(any(), any(), any());
    }

    @Test
    void reconcile_whenAlreadyProcessedByAnotherPath_doesNotPropagateException() {
        Payment payment = pendingPayment(4L, "order-4");
        TossConfirmResponse pg = responseWithStatus("DONE");
        when(tossPaymentsClient.getPaymentByOrderId("order-4")).thenReturn(pg);
        when(paymentConfirmCommandService.completeConfirm(eq(4L), eq("test-payment-key"), eq(pg)))
                .thenThrow(new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT));

        paymentReconciliationService.reconcile(payment);

        // 예외를 던지지 않고 조용히 끝나야 하고, 삼킨 뒤 failPending으로 대체 처리하지도 않아야 한다.
        verify(paymentConfirmCommandService, never()).failPending(any());
    }

    @Test
    void reconcile_whenOtherBusinessExceptionOccurs_rethrows() {
        Payment payment = pendingPayment(5L, "order-5");
        TossConfirmResponse pg = responseWithStatus("DONE");
        when(tossPaymentsClient.getPaymentByOrderId("order-5")).thenReturn(pg);
        when(paymentConfirmCommandService.completeConfirm(eq(5L), eq("test-payment-key"), eq(pg)))
                .thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_STOCK));

        org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> paymentReconciliationService.reconcile(payment));
    }

    @Test
    void reconcile_reversalPendingAndCanceled_failsReversalPending() {
        Payment payment = reversalPendingPayment(6L, "order-6");
        when(tossPaymentsClient.getPaymentByOrderId("order-6")).thenReturn(responseWithStatus("CANCELED"));

        paymentReconciliationService.reconcile(payment);

        verify(paymentConfirmCommandService).failReversalPending(6L);
        verify(paymentConfirmCommandService, never()).completeConfirm(any(), any(), any());
    }

    @Test
    void reconcile_reversalPendingAndDone_doesNotCompleteConfirm() {
        Payment payment = reversalPendingPayment(7L, "order-7");
        when(tossPaymentsClient.getPaymentByOrderId("order-7")).thenReturn(responseWithStatus("DONE"));

        paymentReconciliationService.reconcile(payment);

        verify(paymentConfirmCommandService, never()).completeConfirm(any(), any(), any());
        verify(paymentConfirmCommandService, never()).failReversalPending(any());
    }

    @Test
    void reconcile_approvedIsNoOp() {
        Payment payment = pendingPayment(8L, "order-8");
        payment.markRefundRequested();

        paymentReconciliationService.reconcile(payment);

        verify(tossPaymentsClient, never()).getPaymentByOrderId(any());
    }

    @Test
    void reconcile_queryFails_doesNotChangeState() {
        Payment payment = pendingPayment(9L, "order-9");
        when(tossPaymentsClient.getPaymentByOrderId("order-9"))
                .thenThrow(new RuntimeException("timeout"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> paymentReconciliationService.reconcile(payment));
        verify(paymentConfirmCommandService, never()).failPending(any());
        verify(paymentConfirmCommandService, never()).completeConfirm(any(), any(), any());
    }
}
