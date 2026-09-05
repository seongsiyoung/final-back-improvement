package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** PG 결과가 미확정이거나 장부 반영만 남은 환불을 정리한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundReconciliationService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentCancelService paymentCancelService;
    private final PaymentCommandService paymentCommandService;
    private final RefundTargetFactory refundTargetFactory;
    private final PaymentRepository paymentRepository;

    public void reconcile(PaymentRefund refund) {
        RefundStatus status = refund.getRefundStatus();
        if (status != RefundStatus.PG_PENDING && status != RefundStatus.PG_APPROVED) {
            log.debug("환불 재조정 대상이 아님. refundId={}, status={}", refund.getId(), status);
            return;
        }

        RefundTarget target = refundTargetFactory.from(refund);
        Payment payment = paymentRepository.findByOrder_Id(target.orderId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (status == RefundStatus.PG_APPROVED) {
            retryLedger(target, localRefunded(payment) + target.amount());
            return;
        }

        TossConfirmResponse pg = tossPaymentsClient.getPaymentByOrderId(payment.getPgOrderId());
        int cumulative = pg.getCumulativeCanceledAmount();
        if (cumulative > localRefunded(payment)) {
            paymentCommandService.markPgApproved(target.storeOrderId());
            retryLedger(target, cumulative);
            return;
        }

        paymentCancelService.resumeCancel(target);
    }

    private int localRefunded(Payment payment) {
        return payment.getRefundedAmount() == null ? 0 : payment.getRefundedAmount();
    }

    private void retryLedger(RefundTarget target, int cumulative) {
        try {
            paymentCommandService.applyRefund(
                    target.orderId(), target.storeOrderId(), target.amount(), target.reason(), cumulative);
        } catch (BusinessException e) {
            if (paymentCommandService.isRefundAlreadyApplied(target)) {
                // 장부는 이미 맞았다. 예외를 다시 던지면 성공한 건이 실패로 보인다.
                // 다만 원 예외를 버리면 커밋 이후 후속 처리 실패가 흔적 없이 사라진다.
                log.warn("[REFUND_LEDGER_ALREADY_APPLIED] storeOrderId={}, code={}",
                        target.storeOrderId(), e.getErrorCode(), e);
                return;
            }
            paymentCommandService.markRefundReconciliationRequired(target);
            throw e;
        }
    }
}
