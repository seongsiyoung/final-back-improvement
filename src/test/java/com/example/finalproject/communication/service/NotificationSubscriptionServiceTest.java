package com.example.finalproject.communication.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.global.sse.Service.SseService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@ExtendWith(MockitoExtension.class)
class NotificationSubscriptionServiceTest {

    @Mock
    private NotificationSubscriptionQueryService queryService;

    @Mock
    private SseService sseService;

    @Mock
    private SseEmitter emitter;

    @Test
    void subscribe_registersBeforeLoadingSnapshotAndReplaysAfterTheQueryReturns() {
        NotificationSubscriptionService service = new NotificationSubscriptionService(queryService, sseService);
        NotificationSubscriptionQueryService.SubscriptionSnapshot snapshot =
                new NotificationSubscriptionQueryService.SubscriptionSnapshot(List.<NotificationResponse>of(), 3);
        when(queryService.findUserId("user@example.com")).thenReturn(1L);
        when(sseService.register(1L)).thenReturn(emitter);
        when(queryService.load(1L, 7L)).thenReturn(snapshot);

        service.subscribe("user@example.com", 7L);

        InOrder order = inOrder(queryService, sseService);
        order.verify(queryService).findUserId("user@example.com");
        order.verify(sseService).register(1L);
        order.verify(queryService).load(1L, 7L);
        order.verify(sseService).replay(eq(1L), eq(emitter), eq(snapshot.notifications()), eq(snapshot.unreadCount()));
    }
}
