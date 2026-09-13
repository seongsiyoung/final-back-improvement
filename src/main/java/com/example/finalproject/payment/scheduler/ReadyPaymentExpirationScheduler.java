package com.example.finalproject.payment.scheduler;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.PaymentConfirmCommandService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 결제창을 열어두고 떠난 READY 결제를 종결해 선점을 되돌린다.
 *
 * <p>선점을 되돌리는 다른 경로는 모두 사용자가 다시 행동해야 작동한다. 이 스캔이 없으면
 * 이탈한 장바구니만큼 재고가 영구히 묶인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadyPaymentExpirationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentConfirmCommandService paymentConfirmCommandService;

    @Value("${reconciliation.batch-size:100}")
    private int batchSize;
    @Value("${reservation.ready-expiry-minutes:30}")
    private long readyExpiryMinutes;

    @Scheduled(fixedDelay = 300_000L)
    public void expireStaleReadyPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(readyExpiryMinutes);

        List<Payment> targets = paymentRepository.findExpiredReadyPayments(
                PaymentStatus.READY, threshold, PageRequest.of(0, batchSize));

        for (Payment payment : targets) {
            try {
                paymentConfirmCommandService.expireReadyPayment(payment.getId());
            } catch (Exception e) {
                // 한 건이 실패해도 나머지는 계속 처리한다. 실패한 건은 READY 로 남아 다음 주기에 다시 잡힌다.
                log.error("준비 결제 만료 처리 실패. paymentId={}", payment.getId(), e);
            }
        }
    }
}
