package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.StoreOrder;
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

    public void cancel(StoreOrder storeOrder, int refundAmount, String reason) {

        Long orderId = storeOrder.getOrder().getId();

        Payment payment = paymentRepository.findByOrder_Id(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        paymentCommandService.markRefundRequested(orderId);

        CancelResult result;
        try {
            log.info("[PG_CANCEL_REQUEST] orderId={}, storeOrderId={}, amount={}",
                    orderId, storeOrder.getId(), storeOrder.getFinalPrice());

            String idempotencyKey = TossIdempotencyKeys.forStoreCancel(payment.getId(), storeOrder.getId());
            result = paymentGateway.cancel(payment.getPaymentKey(), refundAmount, reason, idempotencyKey);
        } catch (Exception e) {
            log.error("[PG_CANCEL_ERROR] orderId={}, paymentId={}, error={}",
                    orderId, payment.getId(), e.getMessage(), e);

            paymentCommandService.revertRefundRequestAndMarkFailed(orderId, storeOrder.getId());
            throw new BusinessException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }

        paymentCommandService.applyRefund(
                orderId,
                storeOrder.getId(),
                refundAmount,
                reason,
                result.getCumulativeCanceledAmount()
        );
    }
}
