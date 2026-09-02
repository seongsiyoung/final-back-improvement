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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
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
        if (outcome == ReconciliationOutcome.REFUNDED
                && confirmedAmount != null && confirmedAmount > 0 && confirmedAmount <= payment.getAmount()) {
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
                || outcome != ReconciliationOutcome.NOT_CHARGED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }
        payment.fail();
    }
}
