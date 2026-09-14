package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 구독 결제에서 나가지 못한 보상 취소를 다시 보낸다.
 *
 * <p>일반 결제(ReversalResendService)와 판단은 같고 게이트웨이와 멱등키만 다르다.
 * 구독은 웹훅이 없어 이 스캔이 유일한 복구 수단이다.
 *
 * <p>트랜잭션을 열지 않는다. PG 호출은 트랜잭션 밖이어야 하고, 상태 전이는 호출되는
 * 커맨드 서비스가 각자 자기 트랜잭션에서 한다.
 */
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
