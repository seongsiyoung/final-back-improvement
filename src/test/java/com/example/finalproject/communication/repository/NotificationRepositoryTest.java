package com.example.finalproject.communication.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.communication.domain.Notification;
import com.example.finalproject.communication.enums.NotificationRefType;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class NotificationRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void findAllByUserIdAndIdGreaterThanOrderByIdAsc_returnsOnlyTargetUsersLaterNotificationsInOrder() {
        User targetUser = saveUser("target@example.com", "010-0000-0001");
        User anotherUser = saveUser("another@example.com", "010-0000-0002");

        Notification cursor = saveNotification(targetUser, "cursor");
        Notification firstMissed = saveNotification(targetUser, "first");
        saveNotification(anotherUser, "another-user");
        Notification secondMissed = saveNotification(targetUser, "second");

        List<Notification> replayNotifications = notificationRepository
                .findAllByUserIdAndIdGreaterThanOrderByIdAsc(targetUser.getId(), cursor.getId());

        assertThat(replayNotifications)
                .extracting(Notification::getId)
                .containsExactly(firstMissed.getId(), secondMissed.getId());
    }

    private User saveUser(String email, String phone) {
        return userRepository.save(User.builder()
                .email(email)
                .password("password")
                .name("알림 사용자")
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
}
