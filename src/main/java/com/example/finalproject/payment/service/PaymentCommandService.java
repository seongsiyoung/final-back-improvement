package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.event.StoreOrderRefundCompletedEvent;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final StoreOrderRepository storeOrderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void applyRefund(Long orderId,
                            Long storeOrderId,
                            Integer cancelAmount,
                            String reason,
                            Integer pgCumulativeAmount) {

        Payment payment = findPaymentWithLock(orderId);

        validateRefundRequest(payment, cancelAmount, pgCumulativeAmount);

        saveRefundHistory(storeOrderId, cancelAmount);

        PaymentStatus before = payment.getPaymentStatus();
        updatePaymentStatus(pgCumulativeAmount, payment);

        log.info("[PAYMENT_STATUS_CHANGED] orderId={}, from={}, to={}, cumulativeAmount={}",
                orderId, before, payment.getPaymentStatus(), pgCumulativeAmount);

        publishRefundEvent(storeOrderId, cancelAmount, reason);
    }

    @Transactional
    public void startRefund(RefundTarget target) {

        Payment payment = findPaymentWithLock(target.orderId());

        if (payment.getPaymentStatus() == PaymentStatus.REFUND_REQUESTED) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }

        if (payment.isFullyRefunded()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }

        payment.markRefundRequested();

        paymentRefundRepository.findByStoreOrder_Id(target.storeOrderId())
                .ifPresentOrElse(
                        PaymentRefund::markPgPending,
                        () -> createPgPendingRefund(payment, target));
    }

    private void createPgPendingRefund(Payment payment, RefundTarget target) {
        StoreOrder storeOrder = storeOrderRepository.findById(target.storeOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        paymentRefundRepository.save(
                PaymentRefund.builder()
                        .payment(payment)
                        .storeOrder(storeOrder)
                        .refundAmount(target.amount())
                        .refundReason(target.reason())
                        .refundStatus(RefundStatus.PG_PENDING)
                        .responsibility(RefundResponsibility.PLATFORM)
                        .isSettled(false)
                        .build());
    }

    @Transactional
    public void handleCancelRejection(RefundTarget target) {
        Payment payment = findPaymentWithLock(target.orderId());
        StoreOrder storeOrder = storeOrderRepository.findById(target.storeOrderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        switch (storeOrder.getStatus()) {
            case CANCEL_REQUESTED -> {
                storeOrder.revertCancelRequest();
                payment.revertRefundRequest();
            }
            case REFUND_REQUESTED -> {
                storeOrder.revertRefundRequest();
                payment.revertRefundRequest();
            }
            case REJECT_REQUESTED -> payment.markReconciliationRequired();
            default -> {
                log.error("[CANCEL_REJECTION_STATE_MISMATCH] storeOrderId={}, status={}",
                        target.storeOrderId(), storeOrder.getStatus());
                payment.markReconciliationRequired();
            }
        }

        paymentRefundRepository.findByStoreOrder_Id(target.storeOrderId())
                .ifPresent(PaymentRefund::markPgRejected);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPgApproved(Long storeOrderId) {
        paymentRefundRepository.findByStoreOrder_Id(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND))
                .markPgApproved();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRefundReconciliationRequired(RefundTarget target) {
        findPaymentWithLock(target.orderId()).markReconciliationRequired();
        paymentRefundRepository.findByStoreOrder_Id(target.storeOrderId())
                .ifPresent(PaymentRefund::markReconciliationRequired);
    }

    private Payment findPaymentWithLock(Long orderId) {
        return paymentRepository.findWithLockByOrder_Id(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    private void validateRefundRequest(Payment payment,
                                       Integer cancelAmount,
                                       Integer pgCumulativeAmount) {

        if (payment.getPaymentStatus() != PaymentStatus.REFUND_REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }

        if (payment.isFullyRefunded()) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }

        if (cancelAmount == null || cancelAmount <= 0) {
            throw new BusinessException(ErrorCode.INVALID_CANCEL_AMOUNT);
        }

        if (pgCumulativeAmount == null) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_AMOUNT);
        }

        if (pgCumulativeAmount < cancelAmount ||
                pgCumulativeAmount > payment.getAmount()) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_AMOUNT);
        }
    }

    private void publishRefundEvent(Long storeOrderId, Integer cancelAmount, String reason) {
        eventPublisher.publishEvent(new StoreOrderRefundCompletedEvent(storeOrderId, cancelAmount, reason));
    }

    private static void updatePaymentStatus(Integer pgCumulativeAmount, Payment payment) {
        if (pgCumulativeAmount.equals(payment.getAmount())) {
            payment.Refunded();
        } else {
            payment.applyCumulativeCanceledAmount(pgCumulativeAmount);
        }
    }

    private void saveRefundHistory(Long storeOrderId, Integer cancelAmount) {
        PaymentRefund refund = paymentRefundRepository.findByStoreOrder_Id(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
        refund.adminApprove(cancelAmount);
    }
}
