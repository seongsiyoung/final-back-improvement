package com.example.finalproject.global.sse.Service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.global.sse.repository.SseEmitterRepository;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseServiceTest {

    @Test
    void sendNotification_sendsNotificationIdAsSseIdAndPayloadId() {
        SseEmitterRepository repository = new SseEmitterRepository();
        SseService sseService = new SseService(repository);
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        repository.save(1L, "1_emitter", emitter);

        NotificationResponse notification = new NotificationResponse(
                42L,
                "새 알림",
                "내용",
                NotificationRefType.ORDER,
                LocalDateTime.of(2026, 8, 22, 15, 0));

        sseService.sendNotification(1L, notification);

        assertThat(emitter.sentData)
                .containsExactly("id:42\nevent:notification-created\ndata:", notification, "\n\n");
    }

    @Test
    void sendHeartbeat_sendsEventWithoutChangingLastNotificationId() {
        SseEmitterRepository repository = new SseEmitterRepository();
        SseService sseService = new SseService(repository);
        CapturingSseEmitter firstEmitter = new CapturingSseEmitter();
        CapturingSseEmitter secondEmitter = new CapturingSseEmitter();
        repository.save(1L, "1_emitter", firstEmitter);
        repository.save(2L, "2_emitter", secondEmitter);

        sseService.sendHeartbeat();

        assertThat(firstEmitter.sentData)
                .containsExactly("event:heartbeat\ndata:", "ping", "\n\n");
        assertThat(secondEmitter.sentData)
                .containsExactly("event:heartbeat\ndata:", "ping", "\n\n");
    }

    @Test
    void sendHeartbeat_removesFailedEmitterAndContinuesOtherEmitters() {
        SseEmitterRepository repository = new SseEmitterRepository();
        SseService sseService = new SseService(repository);
        CapturingSseEmitter healthyEmitter = new CapturingSseEmitter();
        repository.save(1L, "failed", new FailingSseEmitter());
        repository.save(1L, "healthy", healthyEmitter);

        sseService.sendHeartbeat();

        assertThat(repository.getEmitterIds(1L)).containsExactly("healthy");
        assertThat(healthyEmitter.sentData)
                .containsExactly("event:heartbeat\ndata:", "ping", "\n\n");
    }

    @Test
    void subscribe_usesSameTwentyFourHourTimeoutAsNginx() {
        SseService sseService = new SseService(new SseEmitterRepository());

        SseEmitter emitter = sseService.subscribe(1L);

        assertThat(emitter.getTimeout()).isEqualTo(24 * 60 * 60 * 1000L);
    }

    private static class CapturingSseEmitter extends SseEmitter {

        private final List<Object> sentData = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) throws IOException {
            sentData.addAll(event.build().stream()
                    .map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .toList());
        }
    }

    private static class FailingSseEmitter extends SseEmitter {

        @Override
        public void send(SseEventBuilder event) throws IOException {
            throw new IOException("connection closed");
        }
    }
}
