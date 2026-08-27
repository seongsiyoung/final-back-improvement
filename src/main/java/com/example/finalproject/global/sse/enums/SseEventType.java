package com.example.finalproject.global.sse.enums;

public enum SseEventType {
    NOTIFICATION_CREATED("notification-created"),
    HEARTBEAT("heartbeat"),
    UNREAD_COUNT("unread-count"),
    CONNECTED("connected"),
    STORE_ORDER_CREATED("store-order-created"),
    STORE_ORDER_UPDATED("store-order-updated"),
    ORDER_CREATED("order-created"),
    NEW_DELIVERY("new-delivery"),
    NEARBY_DELIVERIES("nearby-deliveries"),
    DELIVERY_MATCHED("delivery-matched"),
    DELIVERY_STATUS_CHANGED("delivery-status-changed");

    private final String eventName;

    SseEventType(String eventName) {
        this.eventName = eventName;
    }

    public String getEventName() {
        return eventName;
    }
}
