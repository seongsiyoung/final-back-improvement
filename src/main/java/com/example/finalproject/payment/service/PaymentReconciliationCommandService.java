package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.PaymentResolutionOutcome;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.payment.event.PaymentResolvedEvent;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.finalproject.product.service.StockReservationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentReconciliationCommandService {

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final StockReservationService stockReservationService;

    @Transactional
    public void resolvePayment(Long paymentId, ReconciliationOutcome outcome, Integer confirmedAmount) {
        Payment payment = paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getPaymentStatus() != PaymentStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }
        if (paymentRefundRepository.existsByPayment_IdAndRefundStatusIn(
                paymentId, PaymentRefundRepository.ACTIVE_REFUND_STATUSES)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }
        if (outcome == ReconciliationOutcome.NOT_CHARGED) {
            payment.fail();
            releaseReservationIfNotConfirmed(payment);
            publishPaymentFailed(payment);
            return;
        }
        if (outcome == ReconciliationOutcome.REFUNDED) {
            if (confirmedAmount == null || confirmedAmount <= 0 || confirmedAmount > payment.getAmount()) {
                throw new BusinessException(ErrorCode.INVALID_REFUND_AMOUNT);
            }
            payment.resolveReconciliationAsRefunded(confirmedAmount);
            releaseReservationIfNotConfirmed(payment);
            publishPaymentFailed(payment);
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
    }

    /**
     * 승인까지 간 결제는 completeConfirm() 이 선점을 이미 실재고 차감으로 확정했다. 그 결제에
     * 반납을 부르면 자기 선점이 없으므로 같은 상품에 남아 있는 다른 결제의 선점을 대신 깎는다.
     *
     * <p>승인 완료 결제도 이 API 로 들어온다 — 예를 들어 취소 거절(handleCancelRejection)이
     * REJECT_REQUESTED 주문의 결제를 RECONCILIATION_REQUIRED 로 올린다.
     * paidAt 은 approve() 에서만 채워지므로 확정 여부의 판별자가 된다.
     */
    private void releaseReservationIfNotConfirmed(Payment payment) {
        if (payment.getPaidAt() != null) {
            return;
        }
        stockReservationService.releaseFor(payment.getOrder().getId());
    }

    @Transactional
    public void resolveSubscriptionPayment(Long subscriptionPaymentId, ReconciliationOutcome outcome) {
        SubscriptionPayment payment = subscriptionPaymentRepository.findById(subscriptionPaymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        if (payment.getPaymentStatus() != PaymentStatus.RECONCILIATION_REQUIRED
                || (outcome != ReconciliationOutcome.NOT_CHARGED && outcome != ReconciliationOutcome.REFUNDED)) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }
        payment.fail();

        Subscription subscription = payment.getSubscription();
        if (subscription.getStatus() != SubscriptionStatus.PAYMENT_FAILED) {
            log.error("[SUB_RECONCILE_NOT_REVIVED] 청구 대상이 아닌 구독이라 재청구 복원을 건너뜀. "
                            + "subscriptionPaymentId={}, subscriptionId={}, status={}",
                    subscriptionPaymentId, subscription.getId(), subscription.getStatus());
            return;
        }
        subscription.activate();
        subscription.resetFailCount();
    }

    private void publishPaymentFailed(Payment payment) {
        applicationEventPublisher.publishEvent(new PaymentResolvedEvent(
                payment.getId(), payment.getOrder().getUser().getId(), PaymentResolutionOutcome.FAILED));
    }
}
