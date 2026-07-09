package com.example.finalproject.communication.controller;

import com.example.finalproject.communication.dto.response.NotificationResponse;
import com.example.finalproject.communication.service.NotificationService;
import com.example.finalproject.communication.service.NotificationSubscriptionService;
import com.example.finalproject.global.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationSubscriptionService subscriptionService;

    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(
            Authentication authentication,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        return subscriptionService.subscribe(authentication.getName(), parseLastEventId(lastEventId));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> myUnreadNotifications(
            Authentication authentication) {

        List<NotificationResponse> unreadNotifications = notificationService
                .getUnreadNotifications(authentication.getName());

        return ResponseEntity.ok(ApiResponse.success(unreadNotifications));
    }

    @PatchMapping("/read")
    public ResponseEntity<ApiResponse<Void>> readAll(Authentication authentication) {
        notificationService.readAll(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<Void>> readNotification(
            Authentication authentication,
            @PathVariable Long notificationId) {

        notificationService.readNotification(authentication.getName(), notificationId);
        return ResponseEntity.ok(ApiResponse.success());
    }

    private Long parseLastEventId(String lastEventId) {
        if (lastEventId == null) {
            return null;
        }
        try {
            long parsed = Long.parseLong(lastEventId);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Last-Event-ID must be positive");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Last-Event-ID must be a positive number");
        }
    }
}
