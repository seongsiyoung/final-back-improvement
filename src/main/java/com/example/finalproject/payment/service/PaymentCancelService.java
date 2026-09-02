package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessException;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateWay paymentGateway;
    private final PaymentCommandService paymentCommandService;

    public void cancel(RefundTarget target) {
        paymentCommandService.startRefund(target);
        resumeCancel(target);
    }

    /** 환불 시작이 커밋된 건의 PG 취소부터 이어간다. */
    public void resumeCancel(RefundTarget target) {
        Payment payment = paymentRepository.findByOrder_Id(target.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        CancelResult result = callPgCancel(payment, target);

        paymentCommandService.markPgApproved(target.storeOrderId());

        applyRefundOrFlag(target, result);
    }

    private CancelResult callPgCancel(Payment payment, RefundTarget target) {
        try {
            log.info("[PG_CANCEL_REQUEST] orderId={}, storeOrderId={}, amount={}",
                    target.orderId(), target.storeOrderId(), target.amount());

            String idempotencyKey = TossIdempotencyKeys.forStoreCancel(payment.getId(), target.storeOrderId());
            return paymentGateway.cancel(payment.getPaymentKey(), target.amount(), target.reason(), idempotencyKey);
        } catch (RuntimeException e) {
            PgCallOutcome outcome = PgFailureClassifier.classify(e);

            log.error("[PG_CANCEL_ERROR] orderId={}, paymentId={}, outcome={}, error={}",
                    target.orderId(), payment.getId(), outcome, e.getMessage(), e);

            if (outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                paymentCommandService.handleCancelRejection(target);
            }

            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }
    }

    private void applyRefundOrFlag(RefundTarget target, CancelResult result) {
        try {
            paymentCommandService.applyRefund(
                    target.orderId(),
                    target.storeOrderId(),
                    target.amount(),
                    target.reason(),
                    result.getCumulativeCanceledAmount());
        } catch (DataAccessException e) {
            log.error("[REFUND_APPLY_DB_ERROR] orderId={}, storeOrderId={}",
                    target.orderId(), target.storeOrderId(), e);
            throw e;
        } catch (BusinessException e) {
            log.error("[REFUND_APPLY_RULE_ERROR] orderId={}, storeOrderId={}, code={}",
                    target.orderId(), target.storeOrderId(), e.getErrorCode(), e);
            paymentCommandService.markRefundReconciliationRequired(target);
            throw e;
        }
    }
}
