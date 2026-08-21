package com.example.finalproject.payment.service.pg;

public interface PaymentGateWay {
    CancelResult cancel(String externalPaymentId, int amount, String reason, String idempotencyKey);
}
