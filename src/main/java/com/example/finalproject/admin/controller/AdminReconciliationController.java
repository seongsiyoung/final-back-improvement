package com.example.finalproject.admin.controller;

import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.payment.dto.request.AdminResolveReconciliationRequest;
import com.example.finalproject.payment.service.PaymentReconciliationCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/reconciliations")
public class AdminReconciliationController {

    private final PaymentReconciliationCommandService paymentReconciliationCommandService;

    @PostMapping("/payments/{paymentId}/resolve")
    public ResponseEntity<ApiResponse<Void>> resolvePayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody AdminResolveReconciliationRequest request) {
        paymentReconciliationCommandService.resolvePayment(
                paymentId, request.getOutcome(), request.getConfirmedAmount());
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/subscription-payments/{subscriptionPaymentId}/resolve")
    public ResponseEntity<ApiResponse<Void>> resolveSubscriptionPayment(
            @PathVariable Long subscriptionPaymentId,
            @Valid @RequestBody AdminResolveReconciliationRequest request) {
        paymentReconciliationCommandService.resolveSubscriptionPayment(subscriptionPaymentId, request.getOutcome());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
