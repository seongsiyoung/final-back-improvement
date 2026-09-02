package com.example.finalproject.payment.scheduler;

import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.payment.service.SubscriptionReconciliationService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 미확정으로 멈춘 구독 결제를 집어간다. @Transactional 이 없다. 루프 안에서 PG 를 호출한다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionReconciliationScheduler {

    private static final List<PaymentStatus> TARGET_STATUSES =
            List.of(PaymentStatus.PENDING, PaymentStatus.REVERSAL_PENDING);

    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final SubscriptionReconciliationService subscriptionReconciliationService;

    @Value("${reconciliation.batch-size:100}")
    private int batchSize;
    @Value("${reconciliation.stale-threshold-minutes:5}")
    private long staleThresholdMinutes;

    @Scheduled(fixedDelay = 300_000L)
    public void reconcileStuckSubscriptionPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);

        for (SubscriptionPayment payment : subscriptionPaymentRepository.findReconciliationTargets(
                TARGET_STATUSES, threshold, PageRequest.of(0, batchSize))) {
            try {
                subscriptionReconciliationService.reconcile(payment);
            } catch (Exception e) {
                log.error("구독 결제 재조정 실패. subscriptionPaymentId={}, status={}",
                        payment.getId(), payment.getPaymentStatus(), e);
            }
        }
    }
}
