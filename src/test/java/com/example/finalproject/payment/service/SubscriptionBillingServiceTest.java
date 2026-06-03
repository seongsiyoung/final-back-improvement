package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.domain.SubscriptionProduct;
import com.example.finalproject.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionBillingServiceTest {

    private TossPaymentsClient tossPaymentsClient;
    private SubscriptionPaymentRepository subscriptionPaymentRepository;
    private SubscriptionBillingService subscriptionBillingService;

    @BeforeEach
    void setUp() {
        tossPaymentsClient = mock(TossPaymentsClient.class);
        subscriptionPaymentRepository = mock(SubscriptionPaymentRepository.class);
        when(subscriptionPaymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        subscriptionBillingService = new SubscriptionBillingService(tossPaymentsClient, subscriptionPaymentRepository);
    }

    @Test
    void chargeMonthlyFee_sendsStoredBillingKeyDirectly_toToss() throws Exception {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn("user@test.com");
        when(user.getName()).thenReturn("사용자");

        PaymentMethod paymentMethod = PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("plain-billing-key")
                .customerKey("customer-1")
                .build();

        SubscriptionProduct product = mock(SubscriptionProduct.class);
        when(product.getSubscriptionProductName()).thenReturn("주간 채소 구독");

        Subscription subscription = mock(Subscription.class);
        when(subscription.getId()).thenReturn(1L);
        when(subscription.getPaymentMethod()).thenReturn(paymentMethod);
        when(subscription.getTotalAmount()).thenReturn(15000);
        when(subscription.getUser()).thenReturn(user);
        when(subscription.getSubscriptionProduct()).thenReturn(product);

        String json = """
                {
                  "paymentKey": "pk-1", "orderId": "order-1", "orderName": "구독",
                  "status": "DONE", "approvedAt": "2026-08-20T00:00:00+09:00",
                  "card": { "issuerCode": "61", "acquirerCode": "31", "number": "1234", "cardType": "credit", "ownerType": "personal" }
                }
                """;
        TossBillingApproveResponse response =
                new ObjectMapper().readValue(json, TossBillingApproveResponse.class);
        when(tossPaymentsClient.approveBilling(any(), any())).thenReturn(response);

        subscriptionBillingService.chargeMonthlyFee(subscription);

        org.mockito.Mockito.verify(tossPaymentsClient).approveBilling(
                org.mockito.ArgumentMatchers.eq("plain-billing-key"), any());
    }
}
