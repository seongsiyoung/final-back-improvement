package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.config.TossCircuitBreakerFallback;
import com.example.finalproject.payment.dto.request.TossCancelRequest;
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

/** PG에 반영되지 않은 일반 결제 보상 취소를 재전송한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReversalResendService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentConfirmCommandService paymentConfirmCommandService;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public void resend(Long paymentId, String paymentKey, int amount) {
        try {
            String idempotencyKey = TossIdempotencyKeys.forCompensatingCancel(paymentId);
            circuitBreakerFactory.create("toss-payment")
                    .run(() -> {
                        tossPaymentsClient.cancel(paymentKey,
                                new TossCancelRequest("재고 부족으로 결제 취소", amount), idempotencyKey);
                        return null;
                    }, TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException e) {
            PgCallOutcome outcome = PgFailureClassifier.classify(e);
            log.error("[PG_REVERSAL_RESEND_ERROR] paymentId={}, outcome={}", paymentId, outcome, e);

            if (outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                paymentConfirmCommandService.markConfirmReconciliationRequired(paymentId);
            }
            return;
        }

        log.info("[PG_REVERSAL_RESENT] paymentId={}", paymentId);
        paymentConfirmCommandService.failReversalPending(paymentId);
    }
}
