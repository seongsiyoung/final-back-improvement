package com.example.finalproject.communication.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.finalproject.communication.service.NotificationService;
import com.example.finalproject.communication.service.NotificationSubscriptionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.Authentication;

class NotificationControllerTest {

    @Test
    void subscribe_rejectsZeroLastEventIdBeforeCreatingEmitter() {
        NotificationService notificationService = Mockito.mock(NotificationService.class);
        NotificationSubscriptionService subscriptionService = Mockito.mock(NotificationSubscriptionService.class);
        NotificationController controller = new NotificationController(notificationService, subscriptionService);
        Authentication authentication = Mockito.mock(Authentication.class);

        assertThatThrownBy(() -> controller.subscribe(authentication, "0"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(subscriptionService);
    }

    @Test
    void subscribe_rejectsNonNumericLastEventIdBeforeCreatingEmitter() {
        NotificationService notificationService = Mockito.mock(NotificationService.class);
        NotificationSubscriptionService subscriptionService = Mockito.mock(NotificationSubscriptionService.class);
        NotificationController controller = new NotificationController(notificationService, subscriptionService);
        Authentication authentication = Mockito.mock(Authentication.class);

        assertThatThrownBy(() -> controller.subscribe(authentication, "seven"))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(subscriptionService);
    }
}
