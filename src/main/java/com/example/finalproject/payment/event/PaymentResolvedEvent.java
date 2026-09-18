package com.example.finalproject.payment.event;

import com.example.finalproject.payment.enums.PaymentResolutionOutcome;

public record PaymentResolvedEvent(
        Long paymentId,
        Long userId,
        PaymentResolutionOutcome outcome) {
}
