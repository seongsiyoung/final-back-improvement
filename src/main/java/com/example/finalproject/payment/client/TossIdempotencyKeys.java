package com.example.finalproject.payment.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/** Creates deterministic idempotency keys for Toss API calls. */
public final class TossIdempotencyKeys {

    private TossIdempotencyKeys() {
    }

    public static String forConfirm(Long paymentId) {
        return "confirm-" + paymentId;
    }

    public static String forStoreCancel(Long paymentId, Long storeOrderId) {
        return "cancel-" + paymentId + "-" + storeOrderId;
    }

    public static String forCompensatingCancel(Long paymentId) {
        return "compensate-" + paymentId;
    }

    public static String forSubscriptionCompensatingCancel(Long subscriptionPaymentId) {
        return "sub-compensate-" + subscriptionPaymentId;
    }

    public static String forBillingIssue(String authKey) {
        return "billing-issue-" + sha256Hex(authKey);
    }

    public static String forBillingDelete(String billingKey) {
        return "billing-delete-" + sha256Hex(billingKey);
    }

    public static String forBillingApprove(Long subscriptionId, LocalDate nextPaymentDate) {
        return "billing-approve-" + subscriptionId + "-" + nextPaymentDate;
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }
}
