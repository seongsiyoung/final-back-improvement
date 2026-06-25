package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
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

class SubscriptionChargeCommandServiceTest extends IntegrationTestSupport {

    @Autowired
    private SubscriptionChargeCommandService subscriptionChargeCommandService;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private SubscriptionProductRepository subscriptionProductRepository;
    @Autowired
    private PaymentMethodRepository paymentMethodRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Test
    void startCharge_whenApprovedPaymentAlreadyExistsForCycle_throwsWithoutCreatingNewRow() {
        Subscription subscription = createActiveSubscriptionFixture();
        SubscriptionPayment approved = subscriptionPaymentRepository.save(SubscriptionPayment.builder()
                .subscription(subscription)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(subscription.getTotalAmount())
                .pgOrderId("SUB-ALREADY-" + System.nanoTime())
                .pgProvider("TOSS")
                .paymentStatus(PaymentStatus.PENDING)
                .billingCycleDate(subscription.getNextPaymentDate())
                .build());
        approved.approve("already-key-" + System.nanoTime(), "pg-tx", "테스트카드사", "1234-****-****-5678");
        subscriptionPaymentRepository.save(approved);

        assertThatThrownBy(() -> subscriptionChargeCommandService.startCharge(subscription.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.ALREADY_PROCESSED_PAYMENT));
    }

    @Test
    void startCharge_whenOnlyFailedPaymentExistsForCycle_succeeds() {
        Subscription subscription = createActiveSubscriptionFixture();
        SubscriptionPayment failed = subscriptionPaymentRepository.save(SubscriptionPayment.builder()
                .subscription(subscription)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(subscription.getTotalAmount())
                .pgOrderId("SUB-FAILED-" + System.nanoTime())
                .pgProvider("TOSS")
                .paymentStatus(PaymentStatus.PENDING)
                .build());
        failed.fail();
        subscriptionPaymentRepository.save(failed);

        var result = subscriptionChargeCommandService.startCharge(subscription.getId());

        assertThat(result.subscriptionPayment()).isNotNull();
        assertThat(result.subscriptionPayment().getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void retryingSameCycle_producesSameApproveIdempotencyKey() {
        Subscription subscription = createActiveSubscriptionFixture();

        var firstAttempt = subscriptionChargeCommandService.startCharge(subscription.getId());
        var secondAttempt = subscriptionChargeCommandService.startCharge(subscription.getId());

        assertThat(firstAttempt.nextPaymentDate()).isEqualTo(secondAttempt.nextPaymentDate());
        assertThat(TossIdempotencyKeys.forBillingApprove(subscription.getId(), firstAttempt.nextPaymentDate()))
                .isEqualTo(TossIdempotencyKeys.forBillingApprove(subscription.getId(), secondAttempt.nextPaymentDate()));
    }

    @Test
    void afterCycleAdvances_producesDifferentApproveIdempotencyKey() {
        Subscription subscription = createActiveSubscriptionFixture();
        var beforeAdvance = subscriptionChargeCommandService.startCharge(subscription.getId());

        subscription.moveNextBillingDate();
        subscriptionRepository.save(subscription);

        var afterAdvance = subscriptionChargeCommandService.startCharge(subscription.getId());

        assertThat(TossIdempotencyKeys.forBillingApprove(subscription.getId(), beforeAdvance.nextPaymentDate()))
                .isNotEqualTo(TossIdempotencyKeys.forBillingApprove(subscription.getId(), afterAdvance.nextPaymentDate()));
    }

    private Subscription createActiveSubscriptionFixture() {
        User user = seeder.seedUserWithAddress("sub-key-user-" + System.nanoTime() + "@test.com", "user1234!");
        Store store = seeder.seedStoreWithProducts(0, 0);
        Address address = addressRepository.findByUserOrderByIsDefaultDesc(user).get(0);
        PaymentMethod paymentMethod = paymentMethodRepository.save(PaymentMethod.builder()
                .user(user)
                .methodType(PaymentMethodType.CARD)
                .billingKey("sub-key-billing-" + System.nanoTime())
                .customerKey("sub-key-customer-" + System.nanoTime())
                .cardCompany("테스트카드사")
                .cardNumberMasked("1234-****-****-5678")
                .isDefault(true)
                .build());
        SubscriptionProduct product = subscriptionProductRepository.save(SubscriptionProduct.builder()
                .store(store)
                .subscriptionProductName("주간 채소 구독")
                .description("멱등키 테스트 전용 구독 상품")
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
