package com.example.finalproject.communication.event.listener;

import com.example.finalproject.communication.event.NotificationCreatedEvent;
import com.example.finalproject.global.sse.Service.SseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class NotificationSseListener {

    private final SseService sseService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(NotificationCreatedEvent event) {
        sseService.sendNotification(event.userId(), event.notification());
    }
}
