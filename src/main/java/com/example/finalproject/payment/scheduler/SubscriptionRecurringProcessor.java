package com.example.finalproject.payment.scheduler;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.service.SubscriptionBillingService;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SubscriptionRecurringProcessor {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionBillingService subscriptionBillingService;
    private final SubscriptionRecurringCommandService subscriptionRecurringCommandService;

    public void processSingleSubscription(Long subscriptionId) {
        if (!subscriptionRepository.existsById(subscriptionId)) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND);
        }

        try {
            subscriptionBillingService.chargeMonthlyFee(subscriptionId);
        } catch (Exception e) {
            // 결제 승인 자체가 실패한 경우에만 결제 실패로 기록한다.
            subscriptionRecurringCommandService.markPaymentFailed(subscriptionId);
            throw e;
        }

        // 결제는 이미 성공(승인)했다. 이후 후처리(다음 회차 날짜 갱신, 배송 일정 생성)가
        // 실패해도 결제 자체는 성공한 것이므로 markPaymentFailed로 덮어쓰지 않는다 —
        // 그렇게 하면 "돈은 빠져나갔는데 구독 상태는 결제 실패"로 모순된 상태가 커밋된다.
        subscriptionRecurringCommandService.advanceAfterSuccessfulCharge(subscriptionId);
    }
}
