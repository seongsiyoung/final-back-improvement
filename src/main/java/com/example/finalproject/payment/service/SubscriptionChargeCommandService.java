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
import com.example.finalproject.payment.scheduler.SubscriptionRecurringCommandService;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionChargeCommandService {

    private static final List<PaymentStatus> BILLING_CYCLE_BLOCKING_STATUSES = List.of(
            PaymentStatus.APPROVED,
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.RECONCILIATION_REQUIRED);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final SubscriptionRecurringCommandService subscriptionRecurringCommandService;

    public record ChargeStart(SubscriptionPayment subscriptionPayment, TossBillingApproveRequest request,
                               String billingKey, LocalDate nextPaymentDate) {}

    @Transactional
    public ChargeStart startCharge(Long subscriptionId) {
        Subscription subscription = subscriptionRepository.findWithLockById(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        LocalDate billingCycleDate = subscription.getNextPaymentDate();

        subscriptionPaymentRepository
                .findFirstBySubscription_IdAndBillingCycleDateAndPaymentStatusInOrderByIdDesc(
                        subscriptionId, billingCycleDate, BILLING_CYCLE_BLOCKING_STATUSES)
                .ifPresent(payment -> {
                    throw new SubscriptionChargeBlockedException(payment.getPaymentStatus());
                });

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

        return new ChargeStart(subscriptionPayment, request, paymentMethod.getBillingKey(), subscription.getNextPaymentDate());
    }

    @Transactional
    public SubscriptionPayment completeCharge(Long subscriptionPaymentId, TossBillingApproveResponse response) {
        String koreanNameByCode = CardIssuer.getKoreanNameByCode(response.getCard().getIssuerCode());
        String cardNumber = response.getCard() != null ? response.getCard().getNumber() : null;

        return applyApproval(subscriptionPaymentId, response.getPaymentKey(), koreanNameByCode, cardNumber);
    }

    /** 재조정된 승인을 반영하고 구독 후처리를 완료한다. */
    @Transactional
    public void completeReconciledCharge(Long subscriptionPaymentId,
                                         String paymentKey,
                                         String cardCompany,
                                         String cardNumberMasked) {

        SubscriptionPayment payment =
                applyApproval(subscriptionPaymentId, paymentKey, cardCompany, cardNumberMasked);

        Subscription subscription = payment.getSubscription();

        if (subscription.getStatus() != SubscriptionStatus.ACTIVE
                && subscription.getStatus() != SubscriptionStatus.PAYMENT_FAILED) {
            log.error("[SUB_RECONCILE_NOT_REVIVED] 청구 대상이 아닌 구독이라 후처리를 건너뜀. "
                            + "subscriptionPaymentId={}, subscriptionId={}, status={}",
                    subscriptionPaymentId, subscription.getId(), subscription.getStatus());
            return;
        }

        subscriptionRecurringCommandService.advanceAfterSuccessfulCharge(subscription.getId());
    }

    private SubscriptionPayment applyApproval(Long subscriptionPaymentId,
                                              String paymentKey,
                                              String cardCompany,
                                              String cardNumberMasked) {
        SubscriptionPayment subscriptionPayment = subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .orElseThrow(() -> new IllegalStateException(
                        "SubscriptionPayment not found: " + subscriptionPaymentId));

        subscriptionPayment.approve(paymentKey, null, cardCompany, cardNumberMasked);
        return subscriptionPayment;
    }

    @Transactional
    public void failCharge(Long subscriptionPaymentId) {
        subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .ifPresent(SubscriptionPayment::fail);
    }

    @Transactional
    public void markReversalPending(Long subscriptionPaymentId) {
        subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .filter(payment -> payment.getPaymentStatus() == PaymentStatus.PENDING)
                .ifPresent(SubscriptionPayment::markReversalPending);
    }

    @Transactional
    public void markReconciliationRequired(Long subscriptionPaymentId) {
        subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .filter(payment -> payment.getPaymentStatus() == PaymentStatus.PENDING
                        || payment.getPaymentStatus() == PaymentStatus.REVERSAL_PENDING)
                .ifPresent(SubscriptionPayment::markReconciliationRequired);
    }

    @Transactional
    public void failReversalPending(Long subscriptionPaymentId) {
        subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .filter(payment -> payment.getPaymentStatus() == PaymentStatus.REVERSAL_PENDING)
                .ifPresent(SubscriptionPayment::fail);
    }

    // SUB-{id}-{UUID}-{HHmmss}
    private String makePgOrderId(Subscription subscription) {
        String time = LocalDateTime.now().toLocalTime().toString().replace(":", "");
        return "SUB-" + subscription.getId() + "-" + UUID.randomUUID() + "-" + time.substring(0, 6);
    }
}
