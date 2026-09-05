package com.example.finalproject.communication.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.communication.domain.Notification;
import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.communication.repository.NotificationRepository;
import com.example.finalproject.global.sse.Service.SseService;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationRecoveryIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private NotificationSubscriptionQueryService queryService;

    @Autowired
    private SseService sseService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void reconnect_replaysAllThreeNotificationsCreatedAfterLastReceivedId() {
        User user = saveUser("recovery@example.com", "010-0000-0003");
        Notification lastReceived = saveNotification(user, "기준 알림");
        Notification firstMissed = saveNotification(user, "단절 알림 1");
        Notification secondMissed = saveNotification(user, "단절 알림 2");
        Notification thirdMissed = saveNotification(user, "단절 알림 3");

        NotificationSubscriptionQueryService.SubscriptionSnapshot snapshot =
                queryService.load(user.getId(), lastReceived.getId());
        CapturingSseEmitter emitter = new CapturingSseEmitter();

        sseService.replay(user.getId(), emitter, snapshot.notifications(), snapshot.unreadCount());

        assertThat(snapshot.notifications())
                .extracting(NotificationResponse::getId)
                .containsExactly(firstMissed.getId(), secondMissed.getId(), thirdMissed.getId());
        assertThat(emitter.sentNotifications())
                .extracting(NotificationResponse::getId)
                .containsExactly(firstMissed.getId(), secondMissed.getId(), thirdMissed.getId());
        assertThat(emitter.sentNotificationEventIds())
                .containsExactly(
                        firstMissed.getId().toString(),
                        secondMissed.getId().toString(),
                        thirdMissed.getId().toString());
    }

    private User saveUser(String email, String phone) {
        return userRepository.save(User.builder()
                .email(email)
                .password("password")
                .name("SSE 복구 측정 사용자")
                .phone(phone)
                .termsAgreed(true)
                .privacyAgreed(true)
                .build());
    }

    private Notification saveNotification(User user, String title) {
        return notificationRepository.save(Notification.builder()
                .user(user)
                .title(title)
                .content("content")
                .referenceType(NotificationRefType.ORDER)
                .build());
    }

    private static class CapturingSseEmitter extends SseEmitter {

        private final List<List<Object>> sentFrames = new ArrayList<>();

        @Override
        public void send(SseEventBuilder event) throws IOException {
            sentFrames.add(event.build().stream()
                    .map(ResponseBodyEmitter.DataWithMediaType::getData)
                    .toList());
        }

        private List<NotificationResponse> sentNotifications() {
            return sentFrames.stream()
                    .flatMap(List::stream)
                    .filter(NotificationResponse.class::isInstance)
                    .map(NotificationResponse.class::cast)
                    .toList();
        }

        private List<String> sentNotificationEventIds() {
            return sentFrames.stream()
                    .map(frame -> frame.get(0))
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .filter(header -> header.startsWith("id:"))
                    .map(header -> header.substring("id:".length(), header.indexOf('\n')))
                    .toList();
        }
    }
}
