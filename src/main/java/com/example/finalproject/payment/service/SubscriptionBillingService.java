package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SubscriptionBillingService {

    private final TossPaymentsClient tossPaymentsClient;
    private final SubscriptionChargeCommandService subscriptionChargeCommandService;
    private final PaymentGateWay paymentGateWay;

    public SubscriptionPayment chargeMonthlyFee(Long subscriptionId) {
        SubscriptionChargeCommandService.ChargeStart start =
                subscriptionChargeCommandService.startCharge(subscriptionId);
        Long subscriptionPaymentId = start.subscriptionPayment().getId();
        int amount = start.subscriptionPayment().getAmount();

        TossBillingApproveResponse res;
        try {
            res = tossPaymentsClient.approveBilling(start.billingKey(), start.request());
        } catch (RuntimeException e) {
            subscriptionChargeCommandService.failCharge(subscriptionPaymentId);
            throw e;
        }

        try {
            return subscriptionChargeCommandService.completeCharge(subscriptionPaymentId, res);
        } catch (RuntimeException e) {
            paymentGateWay.cancel(res.getPaymentKey(), amount, "구독 결제 반영 실패로 인한 취소");
            subscriptionChargeCommandService.failCharge(subscriptionPaymentId);
            throw e;
        }
    }
}
