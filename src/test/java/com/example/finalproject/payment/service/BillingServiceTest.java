package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.dto.request.PostBillingKeyIssueRequest;
import com.example.finalproject.payment.dto.response.TossBillingKeyIssueResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.user.domain.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BillingServiceTest {

    private TossPaymentsClient tossPaymentsClient;
    private PaymentMethodRepository paymentMethodRepository;
    private UserLoader userLoader;
    private BillingKeyCommandService billingKeyCommandService;
    private BillingService billingService;

    @BeforeEach
    void setUp() {
        tossPaymentsClient = mock(TossPaymentsClient.class);
        paymentMethodRepository = mock(PaymentMethodRepository.class);
        userLoader = mock(UserLoader.class);
        billingKeyCommandService = mock(BillingKeyCommandService.class);

        billingService = new BillingService(
                tossPaymentsClient, paymentMethodRepository, userLoader, billingKeyCommandService);
    }

    private TossBillingKeyIssueResponse issueResponse(String billingKey) throws Exception {
        String json = """
                {
                  "billingKey": "%s",
                  "customerKey": "customer-1",
                  "card": { "issuerCode": "61", "acquirerCode": "31", "number": "1234", "cardType": "credit", "ownerType": "personal" }
                }
                """.formatted(billingKey);
        return new ObjectMapper().readValue(json, TossBillingKeyIssueResponse.class);
    }

    @Test
    void issueCardBillingKey_delegatesToCommandService_withPreparedContext() throws Exception {
        User user = mock(User.class);
        when(userLoader.loadUserByUsername("user@test.com")).thenReturn(user);

        BillingKeyCommandService.BillingIssuePreparation prep =
                new BillingKeyCommandService.BillingIssuePreparation(user, false);
        when(billingKeyCommandService.prepareIssue(user)).thenReturn(prep);

        TossBillingKeyIssueResponse response = issueResponse("raw-billing-key-from-toss");
        when(tossPaymentsClient.issueBillingKey(any(), any())).thenReturn(response);

        PaymentMethod savedMethod = PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("raw-billing-key-from-toss")
                .customerKey("customer-1")
                .cardCompany("현대")
                .cardNumberMasked("1234")
                .isDefault(true)
                .build();
        when(billingKeyCommandService.completeIssue(user, false, response)).thenReturn(savedMethod);

        PostBillingKeyIssueRequest request = new PostBillingKeyIssueRequest();
        ReflectionTestUtils.setField(request, "authKey", "auth-key");
        ReflectionTestUtils.setField(request, "customerKey", "customer-1");

        billingService.issueCardBillingKey("user@test.com", request);

        verify(billingKeyCommandService).completeIssue(user, false, response);
        verify(tossPaymentsClient, org.mockito.Mockito.never()).deleteBillingKey(any());
    }

    @Test
    void issueCardBillingKey_whenCompleteIssueFails_deletesBillingKeyAsCompensation() throws Exception {
        User user = mock(User.class);
        when(userLoader.loadUserByUsername("user@test.com")).thenReturn(user);

        BillingKeyCommandService.BillingIssuePreparation prep =
                new BillingKeyCommandService.BillingIssuePreparation(user, false);
        when(billingKeyCommandService.prepareIssue(user)).thenReturn(prep);

        TossBillingKeyIssueResponse response = issueResponse("raw-billing-key-from-toss");
        when(tossPaymentsClient.issueBillingKey(any(), any())).thenReturn(response);
        when(billingKeyCommandService.completeIssue(user, false, response))
                .thenThrow(new RuntimeException("DB 저장 실패"));

        PostBillingKeyIssueRequest request = new PostBillingKeyIssueRequest();
        ReflectionTestUtils.setField(request, "authKey", "auth-key");
        ReflectionTestUtils.setField(request, "customerKey", "customer-1");

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> billingService.issueCardBillingKey("user@test.com", request));

        verify(tossPaymentsClient).deleteBillingKey("raw-billing-key-from-toss");
    }

    @Test
    void deletePaymentMethod_loadsBillingKey_callsToss_thenCompletesDelete() {
        when(billingKeyCommandService.loadForDelete("user@test.com", 10L)).thenReturn("plain-billing-key");

        billingService.deletePaymentMethod("user@test.com", 10L);

        verify(tossPaymentsClient).deleteBillingKey("plain-billing-key");
        verify(billingKeyCommandService).completeDelete(10L);
    }
}
