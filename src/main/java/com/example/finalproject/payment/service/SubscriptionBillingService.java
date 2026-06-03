package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.request.TossBillingApproveRequest;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.enums.CardIssuer;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.domain.Subscription;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SubscriptionBillingService {

    private final TossPaymentsClient tossPaymentsClient;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Transactional
    public SubscriptionPayment chargeMonthlyFee(Subscription subscription) {

        PaymentMethod paymentMethod = subscription.getPaymentMethod();

        String pgOrderId = makePgOrderId(subscription);

        SubscriptionPayment subscriptionPayment = SubscriptionPayment.builder()
                .subscription(subscription)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(subscription.getTotalAmount())
                .pgOrderId(pgOrderId)
                .pgProvider("TOSS")
                .paymentStatus(PaymentStatus.PENDING)
                .build();

        subscriptionPayment = subscriptionPaymentRepository.save(subscriptionPayment);

        TossBillingApproveRequest req = TossBillingApproveRequest.builder()
                .amount(subscription.getTotalAmount())
                .customerKey(paymentMethod.getCustomerKey())
                .orderId(pgOrderId)
                .orderName(subscription.getSubscriptionProduct().getSubscriptionProductName())
                .customerEmail(subscription.getUser().getEmail())
                .customerName(subscription.getUser().getName())
                .build();

        TossBillingApproveResponse res =
                tossPaymentsClient.approveBilling(paymentMethod.getBillingKey(), req);

        String koreanNameByCode = CardIssuer.getKoreanNameByCode(res.getCard().getIssuerCode());

        String cardNumber = res.getCard() != null ? res.getCard().getNumber() : null;

        subscriptionPayment.approve(
                res.getPaymentKey(),
                null,
                koreanNameByCode,
                cardNumber
        );

        return subscriptionPayment;
    }

    // SUB-{id}-{UUID}-{HHmmss}
    private String makePgOrderId(Subscription subscription) {
        String time = LocalDateTime.now().toLocalTime().toString().replace(":", "");
        return "SUB-" + subscription.getId() + "-" + UUID.randomUUID() + "-" + time.substring(0, 6);
    }
}

