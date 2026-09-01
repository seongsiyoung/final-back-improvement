package com.example.finalproject.testsupport;

import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.domain.SubscriptionProduct;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionProductRepository;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.AddressRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SubscriptionScenarioSeeder {

    private final LoadTestDataSeeder loadTestDataSeeder;
    private final AddressRepository addressRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final SubscriptionProductRepository subscriptionProductRepository;
    private final SubscriptionRepository subscriptionRepository;

    public Subscription active(String email) {
        User user = loadTestDataSeeder.seedUserWithAddress(email, "user1234!");
        Store store = loadTestDataSeeder.seedStoreWithProducts(0, 0);
        Address address = addressRepository.findByUserOrderByIsDefaultDesc(user).get(0);
        PaymentMethod paymentMethod = paymentMethodRepository.save(PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("sub-billing-" + System.nanoTime())
                .customerKey("sub-customer-" + System.nanoTime())
                .cardCompany("테스트카드사")
                .cardNumberMasked("1234-****-****-5678")
                .isDefault(true)
                .build());
        SubscriptionProduct product = subscriptionProductRepository.save(SubscriptionProduct.builder()
                .store(store)
                .subscriptionProductName("주간 채소 구독")
                .description("결제 시나리오 테스트 구독 상품")
                .price(10000)
                .totalDeliveryCount(4)
                .deliveryCountOfWeek(1)
                .imageUrl(null)
                .build());

        return subscriptionRepository.save(Subscription.builder()
                .user(user)
                .store(store)
                .subscriptionProduct(product)
                .address(address)
                .paymentMethod(paymentMethod)
                .totalAmount(10000)
                .startedAt(LocalDateTime.now())
                .nextPaymentDate(LocalDate.now())
                .deliveryTimeSlot("09:00-12:00")
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }
}
