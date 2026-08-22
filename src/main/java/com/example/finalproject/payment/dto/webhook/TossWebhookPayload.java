package com.example.finalproject.payment.dto.webhook;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class TossWebhookPayload {

    private String eventType;
    private Data data;

    @Getter
    @NoArgsConstructor
    public static class Data {
        private String paymentKey;
        private String orderId;
        private String status;
    }
}
