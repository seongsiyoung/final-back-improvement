package com.example.finalproject.communication.event;

import com.example.finalproject.communication.dto.response.NotificationResponse;

public record NotificationCreatedEvent(Long userId, NotificationResponse notification) {
}
