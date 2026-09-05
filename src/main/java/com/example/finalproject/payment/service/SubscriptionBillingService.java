package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.config.TossCircuitBreakerFallback;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionBillingService {

    private final TossPaymentsClient tossPaymentsClient;
    private final SubscriptionChargeCommandService subscriptionChargeCommandService;
    private final PaymentGateWay paymentGateWay;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    public SubscriptionPayment chargeMonthlyFee(Long subscriptionId) {
        SubscriptionChargeCommandService.ChargeStart start =
                subscriptionChargeCommandService.startCharge(subscriptionId);
        Long subscriptionPaymentId = start.subscriptionPayment().getId();
        int amount = start.subscriptionPayment().getAmount();
        String approveIdempotencyKey = TossIdempotencyKeys.forBillingApprove(subscriptionId, start.nextPaymentDate());

        TossBillingApproveResponse res;
        try {
            res = circuitBreakerFactory.create("toss-billing")
                    .run(() -> tossPaymentsClient.approveBilling(start.billingKey(), start.request(), approveIdempotencyKey),
                            TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException e) {
            PgCallOutcome outcome = PgFailureClassifier.classify(e);
            log.error("[SUB_APPROVE_ERROR] subscriptionPaymentId={}, outcome={}, error={}",
                    subscriptionPaymentId, outcome, e.getMessage(), e);
            if (outcome == PgCallOutcome.NOT_SENT || outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                subscriptionChargeCommandService.failCharge(subscriptionPaymentId);
            }
            throw e;
        }

        try {
            return subscriptionChargeCommandService.completeCharge(subscriptionPaymentId, res);
        } catch (RuntimeException e) {
            subscriptionChargeCommandService.markReversalPending(subscriptionPaymentId);
            try {
                String cancelIdempotencyKey = TossIdempotencyKeys.forSubscriptionCompensatingCancel(subscriptionPaymentId);
                paymentGateWay.cancel(res.getPaymentKey(), amount, "구독 결제 반영 실패로 인한 취소", cancelIdempotencyKey);
            } catch (RuntimeException cancelFailure) {
                PgCallOutcome outcome = PgFailureClassifier.classify(cancelFailure);
                // PG 취소 보상 자체가 실패해도 원래 실패 원인(e)을 대체하지 않는다.
                log.error("[SUB_REVERSAL_ERROR] subscriptionPaymentId={}, paymentKey={}, outcome={}",
                        subscriptionPaymentId, res.getPaymentKey(), outcome, cancelFailure);
                e.addSuppressed(cancelFailure);
                if (outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                    subscriptionChargeCommandService.markReconciliationRequired(subscriptionPaymentId);
                }
                throw e;
            }
            subscriptionChargeCommandService.failReversalPending(subscriptionPaymentId);
            throw e;
        }
    }
}
