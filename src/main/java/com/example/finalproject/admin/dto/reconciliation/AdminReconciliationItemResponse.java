package com.example.finalproject.admin.dto.reconciliation;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record AdminReconciliationItemResponse(
        String type, String actionType, Long id, String status, LocalDateTime updatedAt,
        List<String> allowedOutcomes, boolean requiresAmount,
        String pgOrderId, Integer amount, Integer refundedAmount, String orderNumber,
        Long subscriptionId, LocalDate billingCycleDate,
        Integer refundAmount, String refundReason, Long storeOrderId, String storeOrderStatus,
        String paymentStatus) {

    public static AdminReconciliationItemResponse payment(
            Long id,
            String status,
            LocalDateTime updatedAt,
            String actionType,
            List<String> allowedOutcomes,
            boolean requiresAmount,
            String pgOrderId,
            Integer amount,
            Integer refundedAmount,
            String orderNumber) {
        return new AdminReconciliationItemResponse(
                "PAYMENT", actionType, id, status, updatedAt, allowedOutcomes, requiresAmount,
                pgOrderId, amount, refundedAmount, orderNumber,
                null, null, null, null, null, null, null);
    }

    public static AdminReconciliationItemResponse subscriptionPayment(
            Long id,
            String status,
            LocalDateTime updatedAt,
            List<String> allowedOutcomes,
            String pgOrderId,
            Integer amount,
            Long subscriptionId,
            LocalDate billingCycleDate) {
        return new AdminReconciliationItemResponse(
                "SUBSCRIPTION_PAYMENT", "RESOLVE", id, status, updatedAt, allowedOutcomes, false,
                pgOrderId, amount, null, null,
                subscriptionId, billingCycleDate, null, null, null, null, null);
    }

    public static AdminReconciliationItemResponse refund(
            Long id,
            String status,
            LocalDateTime updatedAt,
            String actionType,
            List<String> allowedOutcomes,
            boolean requiresAmount,
            String pgOrderId,
            Integer refundAmount,
            String refundReason,
            Long storeOrderId,
            String orderNumber) {
        return new AdminReconciliationItemResponse(
                "REFUND", actionType, id, status, updatedAt, allowedOutcomes, requiresAmount,
                pgOrderId, null, null, orderNumber,
                null, null, refundAmount, refundReason, storeOrderId, null, null);
    }

    public static AdminReconciliationItemResponse danglingRequest(
            Long storeOrderId,
            LocalDateTime updatedAt,
            String orderNumber,
            String storeOrderStatus,
            String paymentStatus,
            Integer amount) {
        return new AdminReconciliationItemResponse(
                "DANGLING_REQUEST", "INVESTIGATE", storeOrderId, storeOrderStatus, updatedAt, List.of(), false,
                null, amount, null, orderNumber,
                null, null, null, null, storeOrderId, storeOrderStatus, paymentStatus);
    }
}
