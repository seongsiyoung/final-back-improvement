package com.example.finalproject.payment.event;

import lombok.Getter;

@Getter
public class WebhookEventReceivedEvent {

    private final Long webhookEventId;

    public WebhookEventReceivedEvent(Long webhookEventId) {
        this.webhookEventId = webhookEventId;
    }
}
