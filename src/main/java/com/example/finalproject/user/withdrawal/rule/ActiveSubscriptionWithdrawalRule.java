package com.example.finalproject.user.withdrawal.rule;

import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.withdrawal.dto.BlockedReason;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ActiveSubscriptionWithdrawalRule implements WithdrawalEligibilityRule {

    public static final String CODE = "ACTIVE_SUBSCRIPTION_EXISTS";

    private static final List<PaymentStatus> UNRESOLVED_PAYMENT_STATUSES = List.of(
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.RECONCILIATION_REQUIRED,
            PaymentStatus.REFUND_REQUESTED
    );

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Override
    public Optional<BlockedReason> validate(User user) {
        long activeSubscriptionCount = subscriptionRepository.countByUserIdAndStatusIn(
                user.getId(),
                List.of(
                        SubscriptionStatus.ACTIVE,
                        SubscriptionStatus.PAUSED,
                        SubscriptionStatus.CANCELLATION_PENDING
                )
        );

        boolean blockedBySubscription = activeSubscriptionCount > 0
                || subscriptionPaymentRepository.existsBySubscription_UserIdAndPaymentStatusIn(
                        user.getId(),
                        UNRESOLVED_PAYMENT_STATUSES
                );

        if (blockedBySubscription) {
            return Optional.of(BlockedReason.builder()
                    .code(CODE)
                    .message("진행 중인 구독 또는 결과가 확인되지 않은 구독 결제가 있어 탈퇴할 수 없습니다.")
                    .build());
        }
        return Optional.empty();
    }
}
