package com.example.finalproject.communication.service;

import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.communication.repository.NotificationRepository;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationSubscriptionQueryService {

    private final UserRepository userRepository;
    private final NotificationRepository notificationRepository;

    public Long findUserId(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND))
                .getId();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public SubscriptionSnapshot load(Long userId, Long lastEventId) {
        List<NotificationResponse> notifications = lastEventId == null
                ? List.of()
                : notificationRepository.findAllByUserIdAndIdGreaterThanOrderByIdAsc(userId, lastEventId)
                        .stream()
                        .map(NotificationResponse::from)
                        .toList();
        int unreadCount = notificationRepository.countByUserIdAndIsReadFalse(userId);
        return new SubscriptionSnapshot(notifications, unreadCount);
    }

    public record SubscriptionSnapshot(List<NotificationResponse> notifications, int unreadCount) {
    }
}
