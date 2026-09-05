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

    private static final List<PaymentStatus> UNRESOLVED_STATUSES = List.of(
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
        // subscriptionId만 받아 이 트랜잭션 안에서 새로 조회한다 — 호출부(오케스트레이터)가
        // 다른 트랜잭션에서 로드한 detached 엔티티를 그대로 넘기면, 여기서 lazy 필드
        // (paymentMethod/user/subscriptionProduct)에 접근할 때 LazyInitializationException이
        // 난다. 그래서 엔티티가 아니라 ID를 받아 항상 같은 트랜잭션 안에서 재조회한다.
        // 비관적 락으로 조회한다 — 같은 구독에 대한 동시 startCharge() 호출이
        // 이어지는 가드(existsBy...)~저장 구간을 순차적으로 통과하게 만들어
        // TOCTOU 레이스(코드 리뷰에서 발견)를 좁힌다.
        Subscription subscription = subscriptionRepository.findWithLockById(subscriptionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));

        LocalDate billingCycleDate = subscription.getNextPaymentDate();

        // 같은 결제주기에 이미 승인된 결제가 있으면 재승인하지 않는다. DB UNIQUE
        // 제약 대신 애플리케이션 레벨 가드를 쓰는 이유는 design.md "구현 리뷰에서
        // 확정된 결정" 참고 — nextPaymentDate가 실패 시에는 전진하지 않아 DB
        // UNIQUE(subscription_id, billing_cycle_date)를 걸면 정상적인 재시도까지
        // 막아버린다.
        if (subscriptionPaymentRepository.existsBySubscription_IdAndBillingCycleDateAndPaymentStatusIn(
                subscriptionId, billingCycleDate, UNRESOLVED_STATUSES)) {
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
        String koreanNameByCode = CardIssuer.getKoreanNameByCode(response.getCard().getIssuerCode());
        String cardNumber = response.getCard() != null ? response.getCard().getNumber() : null;

        return applyApproval(subscriptionPaymentId, response.getPaymentKey(), koreanNameByCode, cardNumber);
    }

    /**
     * 재조정이 쓰는 승인 확정과 구독 후처리. 조회 응답은 TossConfirmResponse 라 승인 응답
     * DTO 를 만들 수 없고, 카드사 이름도 코드가 아니라 이름 그대로 온다.
     *
     * <p>두 단계를 한 트랜잭션으로 묶는다. 나누면 승인만 커밋되고 구독이 PAYMENT_FAILED 로
     * 남는데, 재조정 스캔은 PENDING·REVERSAL_PENDING 만 보므로 그 건은 다시 집히지 않는다.
     * PG 호출은 이미 끝난 뒤라 트랜잭션 안에 외부 호출이 들어오지 않는다.
     */
    @Transactional
    public void completeReconciledCharge(Long subscriptionPaymentId,
                                         String paymentKey,
                                         String cardCompany,
                                         String cardNumberMasked) {

        SubscriptionPayment payment =
                applyApproval(subscriptionPaymentId, paymentKey, cardCompany, cardNumberMasked);

        Subscription subscription = payment.getSubscription();

        // 해지를 요청했거나 이미 해지된 구독은 되살리지 않는다. 정상 청구 경로는
        // ACTIVE·PAYMENT_FAILED 만 대상으로 삼아 이 경우를 아예 만나지 않는데,
        // 재조정은 결제 상태로만 대상을 고르므로 여기서 막아야 한다.
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
                .filter(payment -> payment.getPaymentStatus() == PaymentStatus.REVERSAL_PENDING)
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
