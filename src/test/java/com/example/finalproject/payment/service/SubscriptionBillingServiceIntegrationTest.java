package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.payment.scheduler.SubscriptionRecurringProcessor;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.domain.SubscriptionProduct;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionProductRepository;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.TossStub;
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.AddressRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class SubscriptionBillingServiceIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private SubscriptionBillingService subscriptionBillingService;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private SubscriptionProductRepository subscriptionProductRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubscriptionRecurringProcessor subscriptionRecurringProcessor;

    private Subscription seedSubscription() {
        String email = "sub-" + System.nanoTime() + "@test.com";
        User user = seeder.seedUserWithAddress(email, "password1234!");
        Store store = seeder.seedStoreWithProducts(1, 10);
        Address address = addressRepository.findByUserOrderByIsDefaultDesc(user).get(0);

        PaymentMethod paymentMethod = paymentMethodRepository.save(PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("stub-billing-key")
                .customerKey("customer-1")
                .isDefault(true)
                .build());

        SubscriptionProduct product = subscriptionProductRepository.save(SubscriptionProduct.builder()
                .store(store)
                .subscriptionProductName("주간 채소 구독")
                .description("테스트용 구독 상품")
                .price(15000)
                .totalDeliveryCount(4)
                .deliveryCountOfWeek(1)
                .build());

        return subscriptionRepository.save(Subscription.builder()
                .user(user)
                .store(store)
                .subscriptionProduct(product)
                .address(address)
                .paymentMethod(paymentMethod)
                .totalAmount(15000)
                .startedAt(LocalDateTime.now())
                .nextPaymentDate(java.time.LocalDate.now().plusMonths(1))
                .deliveryTimeSlot("08:00~11:00")
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }

    @Test
    void chargeMonthlyFee_approvesAndSavesSubscriptionPayment() {
        toss.stubApproveBillingSuccess();

        Subscription subscription = seedSubscription();

        SubscriptionPayment result = subscriptionBillingService.chargeMonthlyFee(subscription.getId());

        assertThat(result.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.getPaymentKey()).isEqualTo("stub-payment-key");
    }

    /**
     * SubscriptionRecurringProcessor는 더 이상 @Transactional이 아니라서, subscriptionId만 갖고
     * 실제 운영 크론과 동일하게 매 트랜잭션 경계마다 새로 DB에서 Subscription을 재조회한다.
     * 이 테스트는 detached 엔티티의 lazy 필드(paymentMethod/user/subscriptionProduct)에
     * 접근할 때 LazyInitializationException 없이 끝까지 완주하는지를 검증한다.
     */
    @Test
    void processSingleSubscription_doesNotThrowLazyInitializationException() {
        toss.stubApproveBillingSuccess("stub-payment-key-2");

        Subscription subscription = seedSubscription();

        subscriptionRecurringProcessor.processSingleSubscription(subscription.getId());

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getNextPaymentDate()).isAfter(java.time.LocalDate.now());
    }
}
