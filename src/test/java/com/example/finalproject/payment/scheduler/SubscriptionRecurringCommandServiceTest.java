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
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.AddressRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SubscriptionRecurringCommandServiceTest extends IntegrationTestSupport {

    @Autowired
    private SubscriptionRecurringCommandService commandService;
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

    private Subscription seedSubscriptionWithFailCount(int failCount) {
        String email = "sub-cmd-" + System.nanoTime() + "@test.com";
        User user = seeder.seedUserWithAddress(email, "password1234!");
        Store store = seeder.seedStoreWithProducts(1, 10);
        Address address = addressRepository.findByUserOrderByIsDefaultDesc(user).get(0);

        PaymentMethod paymentMethod = paymentMethodRepository.save(PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("stub-billing-key")
                .customerKey("customer-cmd-" + System.nanoTime())
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

        for (int i = 0; i < failCount; i++) {
            subscription.markPaymentFailed();
        }
        return subscriptionRepository.save(subscription);
    }

    @Test
    void advanceAfterSuccessfulCharge_resetsFailCount() {
        Subscription subscription = seedSubscriptionWithFailCount(2);
        assertThat(subscription.getFailCount()).isEqualTo(2);

        commandService.advanceAfterSuccessfulCharge(subscription.getId());

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getFailCount()).isEqualTo(0);
        assertThat(reloaded.getNextRetryAt()).isNull();
    }

    @Test
    void advanceAfterSuccessfulCharge_whenPaymentFailed_reactivatesSubscription() {
        Subscription subscription = seedSubscriptionWithFailCount(1);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PAYMENT_FAILED);

        commandService.advanceAfterSuccessfulCharge(subscription.getId());

        Subscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }
}
