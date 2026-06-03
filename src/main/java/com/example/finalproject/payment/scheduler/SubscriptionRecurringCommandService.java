package com.example.finalproject.payment.scheduler;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.subscription.service.SubscriptionScheduleGenerationService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionRecurringCommandService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionScheduleGenerationService scheduleGenerationService;

    @Transactional
    public void advanceAfterSuccessfulCharge(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
        subscription.moveNextBillingDate();
        scheduleGenerationService.generateSchedule(subscription, LocalDate.now());
    }

    @Transactional
    public void markPaymentFailed(Long subscriptionId) {
        subscriptionRepository.findById(subscriptionId)
                .ifPresent(Subscription::markPaymentFailed);
    }
}
