package com.example.finalproject.payment.scheduler;


import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 2시에 자동결제 실행
 * <p>
 * 대상: - 신규 결제: ACTIVE 상태이면서 nextPaymentDate <= 오늘 - 재시도: PAYMENT_FAILED
 * 상태이면서 nextRetryAt <= 지금이고 failCount가 MAX_RETRIES 미만
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionBillingScheduler {

    /**
     * 최대 재시도 횟수. 이 횟수에 도달하면 더 이상 재시도 대상 조회에 걸리지 않는다
     * (design.md "남은 미결정 사항" 참고 — 최대 재시도 횟수는 이 스케줄러 연결 시점에
     * 확정하기로 했었고, 3회로 정했다: 결제 실패 후 최대 3일에 걸쳐 재시도한다).
     */
    private static final int MAX_RETRIES = 3;

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionRecurringProcessor recurringProcessor;

    @Scheduled(cron = "0 0 2 * * *")
    public void processRecurringPayments() {

        LocalDate today = LocalDate.now();

        List<Long> newChargeIds = subscriptionRepository.findIdsByStatusAndNextPaymentDateLessThanEqual(
                SubscriptionStatus.ACTIVE, today);
        List<Long> retryIds = subscriptionRepository.findIdsRetryTargets(
                SubscriptionStatus.PAYMENT_FAILED, LocalDateTime.now(), MAX_RETRIES);

        List<Long> targetIds = new ArrayList<>(newChargeIds);
        targetIds.addAll(retryIds);

        if (targetIds.isEmpty()) {
            log.info("자동결제 대상 없음 - {}", today);
            return;
        }

        log.info("자동결제 대상 {}건 시작(신규 {}건, 재시도 {}건)", targetIds.size(), newChargeIds.size(), retryIds.size());

        for (Long id : targetIds) {
            try {
                recurringProcessor.processSingleSubscription(id);
            } catch (Exception e) {
                log.error("자동결제 처리 중 오류 - subscriptionId={}", id, e);
            }
        }

        log.info("자동결제 스케줄 종료");
    }
}


