package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** PG에 반영되지 않은 구독 결제 보상 취소를 재전송한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionReversalResendService {

    private final PaymentGateWay paymentGateWay;
    private final SubscriptionChargeCommandService subscriptionChargeCommandService;

    public void resend(Long subscriptionPaymentId, String paymentKey, int amount) {
        try {
            String idempotencyKey =
                    TossIdempotencyKeys.forSubscriptionCompensatingCancel(subscriptionPaymentId);
            paymentGateWay.cancel(paymentKey, amount, "구독 결제 반영 실패로 인한 취소", idempotencyKey);
        } catch (RuntimeException e) {
            PgCallOutcome outcome = PgFailureClassifier.classify(e);
            log.error("[SUB_REVERSAL_RESEND_ERROR] subscriptionPaymentId={}, outcome={}",
                    subscriptionPaymentId, outcome, e);

            if (outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                subscriptionChargeCommandService.markReconciliationRequired(subscriptionPaymentId);
            }
            return;
        }

        log.info("[SUB_REVERSAL_RESENT] subscriptionPaymentId={}", subscriptionPaymentId);
        subscriptionChargeCommandService.failReversalPending(subscriptionPaymentId);
    }
}
