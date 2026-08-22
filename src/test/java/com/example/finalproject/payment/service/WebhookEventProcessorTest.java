package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.WebhookEventRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookEventProcessorTest {

    private WebhookEventRepository webhookEventRepository;
    private PaymentRepository paymentRepository;
    private PaymentReconciliationService paymentReconciliationService;
    private WebhookEventStatusService webhookEventStatusService;
    private WebhookEventProcessor webhookEventProcessor;

    @BeforeEach
    void setUp() {
        webhookEventRepository = mock(WebhookEventRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        paymentReconciliationService = mock(PaymentReconciliationService.class);
        webhookEventStatusService = mock(WebhookEventStatusService.class);
        webhookEventProcessor = new WebhookEventProcessor(
                webhookEventRepository, paymentRepository, paymentReconciliationService, webhookEventStatusService);
    }

    private WebhookEvent webhookEvent(String eventType, String orderId) {
        return WebhookEvent.builder()
                .transmissionId("tx-1")
                .transmissionTime("2026-08-21T00:00:00+09:00")
                .eventType(eventType)
                .orderId(orderId)
                .payload("{}")
                .build();
    }

    @Test
    void process_whenEventNotFound_doesNothing() {
        when(webhookEventRepository.findById(99L)).thenReturn(Optional.empty());

        webhookEventProcessor.process(99L);

        verify(paymentReconciliationService, never()).reconcile(any());
        verify(webhookEventStatusService, never()).markProcessed(any());
        verify(webhookEventStatusService, never()).markFailed(any());
    }

    @Test
    void process_whenEventTypeIsNotPaymentStatusChanged_marksProcessedWithoutReconciling() {
        when(webhookEventRepository.findById(1L)).thenReturn(Optional.of(webhookEvent("CANCEL_STATUS_CHANGED", "order-1")));

        webhookEventProcessor.process(1L);

        verify(paymentReconciliationService, never()).reconcile(any());
        verify(webhookEventStatusService).markProcessed(1L);
    }

    @Test
    void process_whenPaymentNotFoundForOrderId_marksFailed() {
        when(webhookEventRepository.findById(2L)).thenReturn(Optional.of(webhookEvent("PAYMENT_STATUS_CHANGED", "order-missing")));
        when(paymentRepository.findByPgOrderId("order-missing")).thenReturn(Optional.empty());

        webhookEventProcessor.process(2L);

        verify(paymentReconciliationService, never()).reconcile(any());
        verify(webhookEventStatusService).markFailed(2L);
        verify(webhookEventStatusService, never()).markProcessed(any());
    }

    @Test
    void process_whenPaymentFound_reconcilesThenMarksProcessed() {
        Payment payment = mock(Payment.class);
        when(webhookEventRepository.findById(3L)).thenReturn(Optional.of(webhookEvent("PAYMENT_STATUS_CHANGED", "order-3")));
        when(paymentRepository.findByPgOrderId("order-3")).thenReturn(Optional.of(payment));

        webhookEventProcessor.process(3L);

        verify(paymentReconciliationService).reconcile(payment);
        verify(webhookEventStatusService).markProcessed(3L);
    }

    @Test
    void process_whenReconcileThrows_marksFailed() {
        Payment payment = mock(Payment.class);
        when(webhookEventRepository.findById(4L)).thenReturn(Optional.of(webhookEvent("PAYMENT_STATUS_CHANGED", "order-4")));
        when(paymentRepository.findByPgOrderId("order-4")).thenReturn(Optional.of(payment));
        org.mockito.Mockito.doThrow(new RuntimeException("PG 조회 실패"))
                .when(paymentReconciliationService).reconcile(payment);

        webhookEventProcessor.process(4L);

        verify(webhookEventStatusService).markFailed(4L);
        verify(webhookEventStatusService, never()).markProcessed(any());
    }
}
