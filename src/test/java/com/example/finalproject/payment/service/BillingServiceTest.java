package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.dto.request.PostBillingKeyIssueRequest;
import com.example.finalproject.payment.dto.response.TossBillingKeyIssueResponse;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class BillingServiceTest {

    private TossPaymentsClient tossPaymentsClient;
    private PaymentMethodRepository paymentMethodRepository;
    private UserLoader userLoader;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        tossPaymentsClient = mock(TossPaymentsClient.class);
        paymentMethodRepository = mock(PaymentMethodRepository.class);
        userLoader = mock(UserLoader.class);

        billingService = new BillingService(tossPaymentsClient, paymentMethodRepository, userLoader);
    }

    @Test
    void issueCardBillingKey_savesRawBillingKey_notManuallyEncrypted() throws Exception {
        User user = mock(User.class);
        when(userLoader.loadUserByUsername("user@test.com")).thenReturn(user);
        when(paymentMethodRepository.existsByUserAndIsDefaultTrue(user)).thenReturn(false);

        String json = """
                {
                  "billingKey": "raw-billing-key-from-toss",
                  "customerKey": "customer-1",
                  "card": { "issuerCode": "61", "acquirerCode": "31", "number": "1234", "cardType": "credit", "ownerType": "personal" }
                }
                """;
        TossBillingKeyIssueResponse response =
                new ObjectMapper().readValue(json, TossBillingKeyIssueResponse.class);
        when(tossPaymentsClient.issueBillingKey(any(), any())).thenReturn(response);

        PostBillingKeyIssueRequest request = new PostBillingKeyIssueRequest();
        ReflectionTestUtils.setField(request, "authKey", "auth-key");
        ReflectionTestUtils.setField(request, "customerKey", "customer-1");

        billingService.issueCardBillingKey("user@test.com", request);

        ArgumentCaptor<com.example.finalproject.payment.domain.PaymentMethod> captor =
                ArgumentCaptor.forClass(com.example.finalproject.payment.domain.PaymentMethod.class);
        org.mockito.Mockito.verify(paymentMethodRepository).save(captor.capture());

        assertThat(captor.getValue().getBillingKey()).isEqualTo("raw-billing-key-from-toss");
    }

    @Test
    void deletePaymentMethod_sendsStoredBillingKeyDirectly_toToss() {
        User user = mock(User.class);
        when(user.getId()).thenReturn(1L);
        when(userLoader.loadUserByUsername("user@test.com")).thenReturn(user);

        com.example.finalproject.payment.domain.PaymentMethod paymentMethod =
                com.example.finalproject.payment.domain.PaymentMethod.builder()
                        .user(user)
                        .methodType(com.example.finalproject.payment.enums.PaymentMethodType.CARD)
                        .billingKey("plain-billing-key")
                        .customerKey("customer-1")
                        .build();
        when(paymentMethodRepository.findByIdAndUser_Id(10L, 1L)).thenReturn(Optional.of(paymentMethod));

        billingService.deletePaymentMethod("user@test.com", 10L);

        org.mockito.Mockito.verify(tossPaymentsClient).deleteBillingKey("plain-billing-key");
        org.mockito.Mockito.verify(paymentMethodRepository).deleteById(10L);
    }
}
