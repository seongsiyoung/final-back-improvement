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

/** 만료된 READY 결제를 종결해 선점을 반납한다. */
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
                log.error("준비 결제 만료 처리 실패. paymentId={}", payment.getId(), e);
            }
        }
    }
}
