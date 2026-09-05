package com.example.finalproject.payment.scheduler;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.PaymentReconciliationService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결과가 미확정인 채로 멈춘 결제를 뒤늦게 잡아주는 안전망.
 * StoreOrderService의 5분 주기 후처리 스캔(StoreOrderService.java:220)과 같은 패턴.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingPaymentReconciliationScheduler {

    /** PENDING 은 승인 결과 미확정, REVERSAL_PENDING 은 보상 취소 결과 미확정이다. */
    private static final List<PaymentStatus> TARGET_STATUSES =
            List.of(PaymentStatus.PENDING, PaymentStatus.REVERSAL_PENDING);

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationService paymentReconciliationService;

    @Value("${reconciliation.batch-size:100}")
    private int batchSize;
    @Value("${reconciliation.stale-threshold-minutes:5}")
    private long staleThresholdMinutes;

    @Scheduled(fixedDelay = 300_000L)
    public void reconcileStalePayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);
        List<Payment> targets = paymentRepository.findReconciliationTargets(
                TARGET_STATUSES, threshold, PageRequest.of(0, batchSize));

        for (Payment payment : targets) {
            try {
                paymentReconciliationService.reconcile(payment);
            } catch (Exception e) {
                // 한 건이 실패해도 나머지 결제는 계속 처리한다.
                log.error("결제 재조정 실패. paymentId={}, status={}",
                        payment.getId(), payment.getPaymentStatus(), e);
            }
        }
    }
}
