package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentReconciliationCommandService {

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;

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
            return;
        }
        if (outcome == ReconciliationOutcome.REFUNDED) {
            if (confirmedAmount == null || confirmedAmount <= 0 || confirmedAmount > payment.getAmount()) {
                throw new BusinessException(ErrorCode.INVALID_REFUND_AMOUNT);
            }
            payment.resolveReconciliationAsRefunded(confirmedAmount);
            return;
        }
        throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
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
}
