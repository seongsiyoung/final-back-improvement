package com.example.finalproject.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
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
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

class SubscriptionBillingSchedulerTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private SubscriptionBillingScheduler scheduler;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private SubscriptionProductRepository subscriptionProductRepository;
    @Autowired
    private EntityManager entityManager;

    private Subscription seedPaymentFailedSubscription() {
        String email = "sub-sched-" + System.nanoTime() + "@test.com";
        User user = seeder.seedUserWithAddress(email, "password1234!");
        Store store = seeder.seedStoreWithProducts(1, 10);
        Address address = addressRepository.findByUserOrderByIsDefaultDesc(user).get(0);

        PaymentMethod paymentMethod = paymentMethodRepository.save(PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("stub-billing-key")
                .customerKey("customer-sched-" + System.nanoTime())
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

        Subscription subscription = subscriptionRepository.save(Subscription.builder()
                .user(user)
                .store(store)
                .subscriptionProduct(product)
                .address(address)
                .paymentMethod(paymentMethod)
                .totalAmount(15000)
                .startedAt(LocalDateTime.now())
                .nextPaymentDate(LocalDate.now())
                .deliveryTimeSlot("08:00~11:00")
                .status(SubscriptionStatus.PAYMENT_FAILED)
                .build());
        subscription.markPaymentFailed();
        return subscriptionRepository.save(subscription);
    }

    private void backdateNextRetryAt(Long subscriptionId, LocalDateTime timestamp) {
        entityManager.createNativeQuery("UPDATE subscriptions SET next_retry_at = :ts WHERE id = :id")
                .setParameter("ts", timestamp)
                .setParameter("id", subscriptionId)
                .executeUpdate();
        entityManager.clear();
    }

    @Test
    @Transactional
    void processRecurringPayments_whenRetryDue_retriesAndReactivates() {
        toss.stubApproveBillingSuccess("stub-payment-key-retry");
        Subscription subscription = seedPaymentFailedSubscription();
        backdateNextRetryAt(subscription.getId(), LocalDateTime.now().minusMinutes(1));

        scheduler.processRecurringPayments();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(reloaded.getFailCount()).isEqualTo(0);
    }

    @Test
    @Transactional
    void processRecurringPayments_whenNextRetryAtInFuture_doesNotRetry() {
        Subscription subscription = seedPaymentFailedSubscription();
        backdateNextRetryAt(subscription.getId(), LocalDateTime.now().plusDays(1));

        scheduler.processRecurringPayments();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.PAYMENT_FAILED);
    }

    @Test
    @Transactional
    void processRecurringPayments_whenRetryExhausted_doesNotRetry() {
        Subscription subscription = seedPaymentFailedSubscription();
        // 이미 1회 실패(seedPaymentFailedSubscription)에 2회 더 실패시켜 failCount=3으로 만든다.
        subscription.markPaymentFailed();
        subscription.markPaymentFailed();
        subscriptionRepository.save(subscription);
        backdateNextRetryAt(subscription.getId(), LocalDateTime.now().minusMinutes(1));

        scheduler.processRecurringPayments();

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.PAYMENT_FAILED);
    }
}
