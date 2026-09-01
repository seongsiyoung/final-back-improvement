package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCancelService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateWay paymentGateway;
    private final PaymentCommandService paymentCommandService;

    public void cancel(RefundTarget target) {

        Long orderId = target.orderId();

        Payment payment = paymentRepository.findByOrder_Id(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        paymentCommandService.startRefund(target);

        CancelResult result;
        try {
            log.info("[PG_CANCEL_REQUEST] orderId={}, storeOrderId={}, amount={}",
                    orderId, target.storeOrderId(), target.amount());

            String idempotencyKey = TossIdempotencyKeys.forStoreCancel(payment.getId(), target.storeOrderId());
            result = paymentGateway.cancel(payment.getPaymentKey(), target.amount(), target.reason(), idempotencyKey);
        } catch (Exception e) {
            log.error("[PG_CANCEL_ERROR] orderId={}, paymentId={}, error={}",
                    orderId, payment.getId(), e.getMessage(), e);

            paymentCommandService.revertRefundRequestAndMarkFailed(orderId, target.storeOrderId());
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }

        paymentCommandService.applyRefund(
                orderId,
                target.storeOrderId(),
                target.amount(),
                target.reason(),
                result.getCumulativeCanceledAmount()
        );
    }
}
