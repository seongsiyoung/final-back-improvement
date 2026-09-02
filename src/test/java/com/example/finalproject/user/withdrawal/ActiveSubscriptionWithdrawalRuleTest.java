package com.example.finalproject.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.withdrawal.rule.ActiveSubscriptionWithdrawalRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActiveSubscriptionWithdrawalRuleTest {

    @Mock
    private SubscriptionRepository subscriptionRepository;

    @Mock
    private SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Mock
    private User user;

    @Test
    @DisplayName("진행 중인 구독이 없어도 미확정 구독 결제가 있으면 탈퇴를 막는다")
    void blocksWithdrawalWhenOnlyUnresolvedSubscriptionPaymentExists() {
        ActiveSubscriptionWithdrawalRule rule = new ActiveSubscriptionWithdrawalRule(
                subscriptionRepository,
                subscriptionPaymentRepository
        );
        when(user.getId()).thenReturn(1L);
        when(subscriptionRepository.countByUserIdAndStatusIn(eq(1L), argThat(List.of(
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PAUSED,
                SubscriptionStatus.CANCELLATION_PENDING
        )::equals))).thenReturn(0L);
        when(subscriptionPaymentRepository.existsBySubscription_UserIdAndPaymentStatusIn(eq(1L), argThat(List.of(
                PaymentStatus.PENDING,
                PaymentStatus.REVERSAL_PENDING,
                PaymentStatus.RECONCILIATION_REQUIRED,
                PaymentStatus.REFUND_REQUESTED
        )::equals)))
                .thenReturn(true);

        assertThat(rule.validate(user)).isPresent();
    }

    @Test
    @DisplayName("진행 중인 구독이 있으면 구독 결제 조회 없이 탈퇴를 막는다")
    void blocksWithdrawalWhenActiveSubscriptionExists() {
        ActiveSubscriptionWithdrawalRule rule = new ActiveSubscriptionWithdrawalRule(
                subscriptionRepository,
                subscriptionPaymentRepository
        );
        when(user.getId()).thenReturn(1L);
        when(subscriptionRepository.countByUserIdAndStatusIn(eq(1L), argThat(List.of(
                SubscriptionStatus.ACTIVE,
                SubscriptionStatus.PAUSED,
                SubscriptionStatus.CANCELLATION_PENDING
        )::equals))).thenReturn(1L);

        assertThat(rule.validate(user)).isPresent();
        verifyNoInteractions(subscriptionPaymentRepository);
    }
}
