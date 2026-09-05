package com.example.finalproject.payment.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.finalproject.global.sse.Service.SseService;
import com.example.finalproject.global.sse.enums.SseEventType;
import com.example.finalproject.payment.enums.PaymentResolutionOutcome;
import com.example.finalproject.payment.event.PaymentResolvedEvent;
import org.junit.jupiter.api.Test;

class PaymentResolvedSseListenerTest {

    private final SseService sseService = mock(SseService.class);
    private final PaymentResolvedSseListener listener = new PaymentResolvedSseListener(sseService);

    @Test
    void approvedPayment_sendsApprovedEventToCustomer() {
        listener.handle(new PaymentResolvedEvent(1L, 10L, PaymentResolutionOutcome.APPROVED));

        verify(sseService).send(10L, SseEventType.PAYMENT_APPROVED, 1L);
    }

    @Test
    void failedPayment_sendsFailedEventToCustomer() {
        listener.handle(new PaymentResolvedEvent(2L, 20L, PaymentResolutionOutcome.FAILED));

        verify(sseService).send(20L, SseEventType.PAYMENT_FAILED, 2L);
    }
}
