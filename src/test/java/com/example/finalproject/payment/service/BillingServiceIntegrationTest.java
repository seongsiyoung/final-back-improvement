package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.dto.request.PostBillingKeyIssueRequest;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.TossStub;
import com.example.finalproject.user.domain.User;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

class BillingServiceIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private BillingService billingService;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private LoadTestDataSeeder seeder;

    private String email;
    private User user;

    @BeforeEach
    void setUp() {
        email = "billing-" + System.nanoTime() + "@test.com";
        user = seeder.seedUserWithAddress(email, "password1234!");
    }

    @Test
    void issueCardBillingKey_savesPaymentMethod_withRawBillingKey() {
        toss.stubIssueBillingKeySuccess();

        PostBillingKeyIssueRequest request = new PostBillingKeyIssueRequest();
        ReflectionTestUtils.setField(request, "authKey", "auth-key-1");
        ReflectionTestUtils.setField(request, "customerKey", "customer-1");

        billingService.issueCardBillingKey(email, request);

        List<PaymentMethod> methods = paymentMethodRepository.findByUserOrderByIsDefaultDesc(user);
        assertThat(methods).hasSize(1);
        assertThat(methods.get(0).getBillingKey()).isEqualTo("stub-billing-key");
    }

    @Test
    void deletePaymentMethod_removesPaymentMethod() {
        toss.stubIssueBillingKeySuccess();
        toss.stubDeleteBillingKeySuccess();

        PostBillingKeyIssueRequest issueRequest = new PostBillingKeyIssueRequest();
        ReflectionTestUtils.setField(issueRequest, "authKey", "auth-key-2");
        ReflectionTestUtils.setField(issueRequest, "customerKey", "customer-2");
        billingService.issueCardBillingKey(email, issueRequest);

        Long paymentMethodId = paymentMethodRepository.findByUserOrderByIsDefaultDesc(user).get(0).getId();

        billingService.deletePaymentMethod(email, paymentMethodId);

        assertThat(paymentMethodRepository.findByUserOrderByIsDefaultDesc(user)).isEmpty();
    }
}
