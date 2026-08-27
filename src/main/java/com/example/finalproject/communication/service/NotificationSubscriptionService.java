package com.example.finalproject.communication.service;

import com.example.finalproject.global.sse.Service.SseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
public class NotificationSubscriptionService {

    private final NotificationSubscriptionQueryService queryService;
    private final SseService sseService;

    public SseEmitter subscribe(String email, Long lastEventId) {
        Long userId = queryService.findUserId(email);
        SseEmitter emitter = sseService.register(userId);
        NotificationSubscriptionQueryService.SubscriptionSnapshot snapshot = queryService.load(userId, lastEventId);
        sseService.replay(userId, emitter, snapshot.notifications(), snapshot.unreadCount());
        return emitter;
    }
}
