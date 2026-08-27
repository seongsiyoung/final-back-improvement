package com.example.finalproject.communication.repository;

import com.example.finalproject.communication.domain.Notification;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findAllByUserIdAndIsReadFalseOrderByCreatedAtDesc(Long id);

    List<Notification> findAllByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long lastEventId);

    int countByUserIdAndIsReadFalse(Long userId);

    @Modifying(clearAutomatically = true)
    @Query("update Notification n "
            + "set n.isRead = true, n.sentAt = current_timestamp "
            + "where n.user.email = :email and n.isRead = false")
    void markAllReadByUserEmail(String email);

    @Modifying(clearAutomatically = true)
    @Query("update Notification n "
            + "set n.isRead = true, n.sentAt = current_timestamp "
            + "where n.id = :notificationId and n.user.id = :userId")
    void markAsReadByNotificationIdAndUserId(Long notificationId, Long userId);

    boolean existsByIdAndUserId(Long notificationId, Long userId);

}
