package com.example.finalproject.communication.event.listener;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.communication.event.NotificationCreatedEvent;
import com.example.finalproject.global.sse.Service.SseService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationSseListenerTest {

    @Test
    void handle_sendsPersistedNotificationAfterCommit() {
        SseService sseService = mock(SseService.class);
        NotificationSseListener listener = new NotificationSseListener(sseService);
        NotificationResponse notification = new NotificationResponse(
                42L, "title", "content", NotificationRefType.ORDER, LocalDateTime.now());

        listener.handle(new NotificationCreatedEvent(1L, notification));

        verify(sseService).sendNotification(1L, notification);
    }
}
