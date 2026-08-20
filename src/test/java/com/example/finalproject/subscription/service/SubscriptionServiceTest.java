package com.example.finalproject.subscription.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.service.SubscriptionBillingService;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.dto.request.PostSubscriptionRequest;
import com.example.finalproject.subscription.repository.SubscriptionHistoryRepository;
import com.example.finalproject.subscription.repository.SubscriptionProductDayOfWeekRepository;
import com.example.finalproject.subscription.repository.SubscriptionProductItemRepository;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.subscription.repository.SubscriptionStatusLogRepository;
import com.example.finalproject.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionServiceTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionProductItemRepository subscriptionProductItemRepository =
            mock(SubscriptionProductItemRepository.class);
    private final SubscriptionProductDayOfWeekRepository subscriptionProductDayOfWeekRepository =
            mock(SubscriptionProductDayOfWeekRepository.class);
    private final SubscriptionHistoryRepository subscriptionHistoryRepository =
            mock(SubscriptionHistoryRepository.class);
    private final SubscriptionStatusLogRepository subscriptionStatusLogRepository =
            mock(SubscriptionStatusLogRepository.class);
    private final UserLoader userLoader = mock(UserLoader.class);
    private final SubscriptionBillingService subscriptionBillingService = mock(SubscriptionBillingService.class);
    private final SubscriptionStatusService subscriptionStatusService = mock(SubscriptionStatusService.class);
    private final SubscriptionCreationService subscriptionCreationService = mock(SubscriptionCreationService.class);

    private final SubscriptionService subscriptionService = new SubscriptionService(
            subscriptionRepository, subscriptionProductItemRepository, subscriptionProductDayOfWeekRepository,
            subscriptionHistoryRepository, subscriptionStatusLogRepository, userLoader,
            subscriptionBillingService, subscriptionStatusService, subscriptionCreationService);

    private Subscription subscriptionWithId(Long id) {
        Subscription subscription = mock(Subscription.class);
        when(subscription.getId()).thenReturn(id);
        return subscription;
    }

    @BeforeEach
    void setUp() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userLoader.loadUserByUsername("user@test.com")).thenReturn(user);
    }

    @Test
    void create_onChargeFailure_marksPaymentFailed_withoutActivating() {
        Subscription subscription = subscriptionWithId(1L);
        when(subscriptionCreationService.createPendingSubscription(any(), any(), any())).thenReturn(subscription);
        when(subscriptionBillingService.chargeMonthlyFee(1L)).thenThrow(new RuntimeException("PG 승인 실패"));

        org.junit.jupiter.api.Assertions.assertThrows(
                com.example.finalproject.global.exception.custom.BusinessException.class,
                () -> subscriptionService.create("user@test.com", mock(PostSubscriptionRequest.class)));

        verify(subscriptionStatusService).markPaymentFailed(1L);
        verify(subscriptionStatusService, org.mockito.Mockito.never()).activateAfterFirstPayment(1L);
    }

    @Test
    void create_onActivationFailure_doesNotMarkPaymentFailed() {
        Subscription subscription = subscriptionWithId(1L);
        when(subscriptionCreationService.createPendingSubscription(any(), any(), any())).thenReturn(subscription);
        when(subscriptionBillingService.chargeMonthlyFee(1L)).thenReturn(mock(SubscriptionPayment.class));
        org.mockito.Mockito.doThrow(new RuntimeException("활성화 반영 실패"))
                .when(subscriptionStatusService).activateAfterFirstPayment(1L);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> subscriptionService.create("user@test.com", mock(PostSubscriptionRequest.class)));

        // 결제는 이미 성공했으므로 후처리 실패를 결제 실패로 잘못 기록하면 안 된다.
        verify(subscriptionStatusService, org.mockito.Mockito.never()).markPaymentFailed(1L);
    }
}
