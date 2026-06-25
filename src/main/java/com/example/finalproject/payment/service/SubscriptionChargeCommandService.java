package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.request.TossBillingApproveRequest;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.enums.CardIssuer;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionChargeCommandService {

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;

    public record ChargeStart(SubscriptionPayment subscriptionPayment, TossBillingApproveRequest request,
                               String billingKey, LocalDate nextPaymentDate) {}

    @Transactional
    public ChargeStart startCharge(Long subscriptionId) {
        // subscriptionId만 받아 이 트랜잭션 안에서 새로 조회한다 — 호출부(오케스트레이터)가
        // 다른 트랜잭션에서 로드한 detached 엔티티를 그대로 넘기면, 여기서 lazy 필드
        // (paymentMethod/user/subscriptionProduct)에 접근할 때 LazyInitializationException이
        // 난다. 그래서 엔티티가 아니라 ID를 받아 항상 같은 트랜잭션 안에서 재조회한다.
        Subscription subscription = subscriptionRepository.findById(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        LocalDate billingCycleDate = subscription.getNextPaymentDate();

        // 같은 결제주기에 이미 승인된 결제가 있으면 재승인하지 않는다. DB UNIQUE
        // 제약 대신 애플리케이션 레벨 가드를 쓰는 이유는 design.md "구현 리뷰에서
        // 확정된 결정" 참고 — nextPaymentDate가 실패 시에는 전진하지 않아 DB
        // UNIQUE(subscription_id, billing_cycle_date)를 걸면 정상적인 재시도까지
        // 막아버린다.
        if (subscriptionPaymentRepository.existsBySubscription_IdAndBillingCycleDateAndPaymentStatus(
                subscriptionId, billingCycleDate, PaymentStatus.APPROVED)) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }

        PaymentMethod paymentMethod = subscription.getPaymentMethod();
        String pgOrderId = makePgOrderId(subscription);

        SubscriptionPayment subscriptionPayment = subscriptionPaymentRepository.save(
                SubscriptionPayment.builder()
                        .subscription(subscription)
                        .paymentMethod(PaymentMethodType.CARD)
                        .amount(subscription.getTotalAmount())
                        .pgOrderId(pgOrderId)
                        .pgProvider("TOSS")
                        .paymentStatus(PaymentStatus.PENDING)
                        .billingCycleDate(billingCycleDate)
                        .build());

        TossBillingApproveRequest request = TossBillingApproveRequest.builder()
                .amount(subscription.getTotalAmount())
                .customerKey(paymentMethod.getCustomerKey())
                .orderId(pgOrderId)
                .orderName(subscription.getSubscriptionProduct().getSubscriptionProductName())
                .customerEmail(subscription.getUser().getEmail())
                .customerName(subscription.getUser().getName())
                .build();

        // billingKey도 이 트랜잭션 안에서 미리 꺼내 반환한다 — 오케스트레이터가
        // 트랜잭션 밖에서 subscription.getPaymentMethod()를 다시 호출하지 않도록.
        return new ChargeStart(subscriptionPayment, request, paymentMethod.getBillingKey(), subscription.getNextPaymentDate());
    }

    @Transactional
    public SubscriptionPayment completeCharge(Long subscriptionPaymentId, TossBillingApproveResponse response) {
        SubscriptionPayment subscriptionPayment = subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "SubscriptionPayment not found: " + subscriptionPaymentId));

        String koreanNameByCode = CardIssuer.getKoreanNameByCode(response.getCard().getIssuerCode());
        String cardNumber = response.getCard() != null ? response.getCard().getNumber() : null;

        subscriptionPayment.approve(response.getPaymentKey(), null, koreanNameByCode, cardNumber);
        return subscriptionPayment;
    }

    @Transactional
    public void failCharge(Long subscriptionPaymentId) {
        subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .ifPresent(SubscriptionPayment::fail);
    }

    // SUB-{id}-{UUID}-{HHmmss}
    private String makePgOrderId(Subscription subscription) {
        String time = LocalDateTime.now().toLocalTime().toString().replace(":", "");
        return "SUB-" + subscription.getId() + "-" + UUID.randomUUID() + "-" + time.substring(0, 6);
    }
}
