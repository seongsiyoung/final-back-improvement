package com.example.finalproject.payment.listener;

import com.example.finalproject.global.sse.Service.SseService;
import com.example.finalproject.global.sse.enums.SseEventType;
import com.example.finalproject.payment.enums.PaymentResolutionOutcome;
import com.example.finalproject.payment.event.PaymentResolvedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class PaymentResolvedSseListener {

    private final SseService sseService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(PaymentResolvedEvent event) {
        SseEventType eventType = event.outcome() == PaymentResolutionOutcome.APPROVED
                ? SseEventType.PAYMENT_APPROVED
                : SseEventType.PAYMENT_FAILED;
        sseService.send(event.userId(), eventType, event.paymentId());
    }
}
