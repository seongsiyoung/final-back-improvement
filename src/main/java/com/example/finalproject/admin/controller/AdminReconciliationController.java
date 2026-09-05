package com.example.finalproject.admin.controller;

import com.example.finalproject.admin.dto.reconciliation.AdminActionRequiredListResponse;
import com.example.finalproject.admin.dto.reconciliation.AdminAutoRecoveryWaitingResponse;
import com.example.finalproject.admin.service.AdminReconciliationService;
import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.payment.dto.request.AdminResolveReconciliationRequest;
import com.example.finalproject.payment.service.PaymentReconciliationCommandService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final AdminReconciliationService adminReconciliationService;

    @GetMapping("/action-required")
    public ResponseEntity<ApiResponse<AdminActionRequiredListResponse>> getActionRequired(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        AdminActionRequiredListResponse response = adminReconciliationService.getActionRequired(
                authentication.getName(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/auto-recovery")
    public ResponseEntity<ApiResponse<AdminAutoRecoveryWaitingResponse>> getAutoRecoveryWaiting(
            Authentication authentication,
            @PageableDefault(size = 20) Pageable pageable) {
        AdminAutoRecoveryWaitingResponse response = adminReconciliationService.getAutoRecoveryWaiting(
                authentication.getName(), pageable);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

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
