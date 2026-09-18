package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** PG 호출 뒤 재조정 관측값만 짧은 별도 트랜잭션으로 남긴다. */
@Service
@RequiredArgsConstructor
public class ReconciliationAttemptCommandService {

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;

    @Transactional
    public void recordPaymentAttempt(Long paymentId, boolean unresolvedAfterSuccessfulLookup) {
        var payment = paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.recordReconciliationAttempt(
                unresolvedAfterSuccessfulLookup && isPaymentReconciliationTarget(payment.getPaymentStatus()));
    }

    @Transactional
    public void recordRefundAttempt(Long refundId, boolean unresolvedAfterSuccessfulLookup) {
        var refund = paymentRefundRepository.findWithLockById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        refund.recordReconciliationAttempt(
                unresolvedAfterSuccessfulLookup && isRefundReconciliationTarget(refund.getRefundStatus()));
    }

    @Transactional
    public void recordSubscriptionPaymentAttempt(Long subscriptionPaymentId, boolean unresolvedAfterSuccessfulLookup) {
        var payment = subscriptionPaymentRepository.findWithLockById(subscriptionPaymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        payment.recordReconciliationAttempt(
                unresolvedAfterSuccessfulLookup && isPaymentReconciliationTarget(payment.getPaymentStatus()));
    }

    private boolean isPaymentReconciliationTarget(PaymentStatus status) {
        return status == PaymentStatus.PENDING || status == PaymentStatus.REVERSAL_PENDING;
    }

    private boolean isRefundReconciliationTarget(RefundStatus status) {
        return status == RefundStatus.PG_PENDING || status == RefundStatus.PG_APPROVED;
    }
}
