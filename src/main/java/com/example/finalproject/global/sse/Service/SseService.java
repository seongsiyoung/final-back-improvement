package com.example.finalproject.global.sse.Service;

import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.global.sse.enums.SseEventType;
import com.example.finalproject.global.sse.repository.SseEmitterRepository;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
@RequiredArgsConstructor
@Slf4j
public class SseService {

    private static final long TIMEOUT = 24 * 60 * 60 * 1000L;
    private final SseEmitterRepository repository;

    public SseEmitter subscribe(Long userId) {
        String emitterId = userId + "_" + UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(TIMEOUT);

        repository.save(userId, emitterId, emitter);

        Runnable cleanup = () -> repository.remove(userId, emitterId);

        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        try {
            emitter.send(SseEmitter.event()
                    .name(SseEventType.CONNECTED.getEventName())
                    .data("connected"));
        } catch (IOException e) {
            cleanup.run();
        }

        return emitter;
    }

    public void send(Long userId, SseEventType eventType, Object data) {
        send(userId, eventType, data, null);
    }

    public void sendNotification(Long userId, NotificationResponse notification) {
        send(userId, SseEventType.NOTIFICATION_CREATED, notification, notification.getId().toString());
    }

    @Scheduled(fixedDelay = 30_000L)
    public void sendHeartbeat() {
        for (Long userId : repository.getUserIds()) {
            send(userId, SseEventType.HEARTBEAT, "ping");
        }
    }

    private void send(Long userId, SseEventType eventType, Object data, String eventId) {
        Set<String> emitterIds = repository.getEmitterIds(userId);

        for (String emitterId : emitterIds) {
            SseEmitter emitter = repository.get(emitterId);
            if (emitter == null) {
                continue;
            }

            try {
                SseEmitter.SseEventBuilder event = SseEmitter.event();
                if (eventId != null) {
                    event.id(eventId);
                }
                event.name(eventType.getEventName()).data(data);
                emitter.send(event);
            } catch (IOException e) {
                repository.remove(userId, emitterId);
            }
        }
    }
}
