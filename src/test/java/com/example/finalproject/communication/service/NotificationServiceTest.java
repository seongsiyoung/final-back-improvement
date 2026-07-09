package com.example.finalproject.communication.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.finalproject.communication.domain.Notification;
import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.communication.event.NotificationCreatedEvent;
import com.example.finalproject.communication.event.UnreadCountChangedEvent;
import com.example.finalproject.communication.repository.NotificationRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class NotificationServiceTest {

    @Test
    void createNotification_publishesPersistedNotificationAndUnreadCountEvents() {
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        NotificationService service = new NotificationService(notificationRepository, userRepository, eventPublisher);
        User user = mock(User.class);
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(user));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(invocation -> {
            Notification notification = invocation.getArgument(0);
            ReflectionTestUtils.setField(notification, "id", 42L);
            return notification;
        });
        when(notificationRepository.countByUserIdAndIsReadFalse(1L)).thenReturn(3);

        service.createNotification(1L, "title", "content", NotificationRefType.ORDER);

        InOrder order = inOrder(eventPublisher);
        org.mockito.ArgumentCaptor<NotificationCreatedEvent> notificationEvent =
                org.mockito.ArgumentCaptor.forClass(NotificationCreatedEvent.class);
        order.verify(eventPublisher).publishEvent(notificationEvent.capture());
        order.verify(eventPublisher).publishEvent(any(UnreadCountChangedEvent.class));
        assertThat(notificationEvent.getValue().userId()).isEqualTo(1L);
        assertThat(notificationEvent.getValue().notification().getId()).isEqualTo(42L);
    }
}
