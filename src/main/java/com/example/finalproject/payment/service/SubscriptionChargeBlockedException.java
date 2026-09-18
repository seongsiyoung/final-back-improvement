package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.enums.PaymentStatus;
import lombok.Getter;

@Getter
public class SubscriptionChargeBlockedException extends BusinessException {

    private final PaymentStatus blockingPaymentStatus;

    public SubscriptionChargeBlockedException(PaymentStatus blockingPaymentStatus) {
        super(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        this.blockingPaymentStatus = blockingPaymentStatus;
    }
}
