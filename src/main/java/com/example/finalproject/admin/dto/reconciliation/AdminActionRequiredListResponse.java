package com.example.finalproject.admin.dto.reconciliation;

import org.springframework.data.domain.Page;

public record AdminActionRequiredListResponse(
        Page<AdminReconciliationItemResponse> payments,
        Page<AdminReconciliationItemResponse> subscriptionPayments,
        Page<AdminReconciliationItemResponse> refunds,
        Page<AdminReconciliationItemResponse> danglingRequests) {
}
