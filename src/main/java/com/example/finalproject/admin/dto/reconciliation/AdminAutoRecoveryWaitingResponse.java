package com.example.finalproject.admin.dto.reconciliation;

import org.springframework.data.domain.Page;

public record AdminAutoRecoveryWaitingResponse(
        AdminReconciliationSummaryResponse summary,
        Page<AdminAutoRecoveryItemResponse> payments,
        Page<AdminAutoRecoveryItemResponse> subscriptionPayments,
        Page<AdminAutoRecoveryItemResponse> refunds) {
}
