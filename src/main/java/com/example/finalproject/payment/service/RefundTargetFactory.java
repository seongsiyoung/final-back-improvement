package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class RefundTargetFactory {

    private final PaymentRefundRepository paymentRefundRepository;

    @Transactional(readOnly = true)
    public RefundTarget from(PaymentRefund detached) {
        PaymentRefund refund = paymentRefundRepository.findById(detached.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));
        StoreOrder storeOrder = refund.getStoreOrder();
        return new RefundTarget(
                storeOrder.getOrder().getId(), storeOrder.getId(),
                refund.getRefundAmount(), refund.getRefundReason());
    }
}
