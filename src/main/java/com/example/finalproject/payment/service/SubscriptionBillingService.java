package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.config.TossCircuitBreakerFallback;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
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
            subscriptionChargeCommandService.failCharge(subscriptionPaymentId);
            throw e;
        }

        try {
            return subscriptionChargeCommandService.completeCharge(subscriptionPaymentId, res);
        } catch (RuntimeException e) {
            try {
                String cancelIdempotencyKey = TossIdempotencyKeys.forSubscriptionCompensatingCancel(subscriptionPaymentId);
                paymentGateWay.cancel(res.getPaymentKey(), amount, "구독 결제 반영 실패로 인한 취소", cancelIdempotencyKey);
            } catch (RuntimeException cancelFailure) {
                // PG 취소 보상 자체가 실패해도 원래 실패 원인(e)을 대체하지 않는다.
                log.error("구독 결제 반영 실패 후 PG 취소 보상도 실패함. subscriptionPaymentId={}, paymentKey={}",
                        subscriptionPaymentId, res.getPaymentKey(), cancelFailure);
                e.addSuppressed(cancelFailure);
            }
            // 취소 보상이 실패해도 failCharge는 반드시 호출한다 — 그렇지 않으면 SubscriptionPayment가
            // PENDING에 영구히 남는다.
            subscriptionChargeCommandService.failCharge(subscriptionPaymentId);
            throw e;
        }
    }
}
