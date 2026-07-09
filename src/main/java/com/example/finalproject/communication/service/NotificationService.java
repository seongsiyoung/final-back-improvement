package com.example.finalproject.communication.service;

import com.example.finalproject.communication.domain.Notification;
import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.communication.event.UnreadCountChangedEvent;
import com.example.finalproject.communication.event.NotificationCreatedEvent;
import com.example.finalproject.communication.repository.NotificationRepository;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void createNotification(
            Long userId,
            String title,
            String content,
            NotificationRefType refType) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Notification notification = Notification.builder()
                .user(user)
                .title(title)
                .content(content)
                .referenceType(refType)
                .build();

        Notification saved = notificationRepository.save(notification);

        int unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        eventPublisher.publishEvent(new NotificationCreatedEvent(userId, NotificationResponse.from(saved)));
        eventPublisher.publishEvent(new UnreadCountChangedEvent(userId, unreadCount));
    }

    public List<NotificationResponse> getUnreadNotifications(String email) {
        User user = getUser(email);

        return notificationRepository
                .findAllByUserIdAndIsReadFalseOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(NotificationResponse::from)
                .toList();
    }

    @Transactional
    public void readAll(String email) {
        User user = getUser(email);

        notificationRepository.markAllReadByUserEmail(email);

        int unreadCount = notificationRepository.countByUserIdAndIsReadFalse(user.getId());
        eventPublisher.publishEvent(new UnreadCountChangedEvent(user.getId(), unreadCount));
    }

    @Transactional
    public void readNotification(String email, Long notificationId) {
        User user = getUser(email);

        if (!notificationRepository.existsByIdAndUserId(notificationId, user.getId())) {
            throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND);
        }

        notificationRepository.markAsReadByNotificationIdAndUserId(notificationId, user.getId());

        int unreadCount = notificationRepository.countByUserIdAndIsReadFalse(user.getId());
        eventPublisher.publishEvent(new UnreadCountChangedEvent(user.getId(), unreadCount));
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    }
}
