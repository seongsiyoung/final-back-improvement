package com.example.finalproject.payment.scheduler;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.PaymentReconciliationService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 웹훅이 놓친(서버 재시작, 처리 중 예외 등) PENDING 결제를 뒤늦게 잡아주는 안전망.
 * StoreOrderService의 5분 주기 후처리 스캔(StoreOrderService.java:220)과 같은 패턴.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingPaymentReconciliationScheduler {

    private static final long STALE_THRESHOLD_MINUTES = 5;

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationService paymentReconciliationService;

    @Scheduled(fixedDelay = 300_000L)
    public void reconcileStalePendingPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);
        List<Payment> stalePayments = paymentRepository.findByPaymentStatusAndUpdatedAtBefore(
                PaymentStatus.PENDING, threshold);

        for (Payment payment : stalePayments) {
            try {
                paymentReconciliationService.reconcile(payment);
            } catch (Exception e) {
                // 한 건이 실패해도 나머지 결제는 계속 처리한다.
                log.error("PENDING 결제 재조회 배치 처리 실패. paymentId={}", payment.getId(), e);
            }
        }
    }
}
