package com.example.finalproject.payment.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TossIdempotencyKeysTest {

    @Test
    void forConfirm_isDeterministicPerPaymentKey() {
        assertThat(TossIdempotencyKeys.forConfirm(42L, "payment-key-a"))
                .isEqualTo(TossIdempotencyKeys.forConfirm(42L, "payment-key-a"))
                .startsWith("confirm-42-")
                .doesNotContain("payment-key-a");
    }

    @Test
    void forConfirm_differsWhenPaymentKeyChangesForSamePayment() {
        assertThat(TossIdempotencyKeys.forConfirm(42L, "payment-key-a"))
                .isNotEqualTo(TossIdempotencyKeys.forConfirm(42L, "payment-key-b"));
    }

    @Test
    void forStoreCancel_differsAcrossStoreOrdersOfSamePayment() {
        String cancelForStoreA = TossIdempotencyKeys.forStoreCancel(10L, 100L);
        String cancelForStoreB = TossIdempotencyKeys.forStoreCancel(10L, 200L);

        assertThat(cancelForStoreA).isNotEqualTo(cancelForStoreB);
        assertThat(cancelForStoreA).isEqualTo("cancel-10-100");
    }

    @Test
    void forStoreCancel_isDeterministicForSameStoreOrder() {
        assertThat(TossIdempotencyKeys.forStoreCancel(10L, 100L))
                .isEqualTo(TossIdempotencyKeys.forStoreCancel(10L, 100L));
    }

    @Test
    void forCompensatingCancel_isSeparateNamespaceFromStoreCancel() {
        String compensating = TossIdempotencyKeys.forCompensatingCancel(10L);
        String storeCancel = TossIdempotencyKeys.forStoreCancel(10L, 100L);

        assertThat(compensating).isNotEqualTo(storeCancel).isEqualTo("compensate-10");
    }

    @Test
    void forSubscriptionCompensatingCancel_isDeterministic() {
        assertThat(TossIdempotencyKeys.forSubscriptionCompensatingCancel(7L))
                .isEqualTo("sub-compensate-7");
    }

    @Test
    void forBillingIssue_usesFullSha256WithoutExposingAuthKey() {
        String key = TossIdempotencyKeys.forBillingIssue("auth-abc");

        assertThat(key)
                .isEqualTo("billing-issue-" +
                        "e83a7f0edcbac1aa2664a6b7172e74e7bafe99bd71c48e643a188de573ad2d64")
                .doesNotContain("auth-abc");
        assertThat(key.substring("billing-issue-".length())).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void forBillingIssue_isDeterministic() {
        assertThat(TossIdempotencyKeys.forBillingIssue("auth-abc"))
                .isEqualTo(TossIdempotencyKeys.forBillingIssue("auth-abc"));
    }

    @Test
    void forBillingDelete_usesFullSha256WithoutExposingBillingKey() {
        String key = TossIdempotencyKeys.forBillingDelete("billing-xyz");

        assertThat(key)
                .isEqualTo("billing-delete-" +
                        "cb806a5ad4521414e2bb8f0e9896a55dfa7027efe4014f8762a7abd464003278")
                .doesNotContain("billing-xyz");
        assertThat(key.substring("billing-delete-".length())).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void forBillingDelete_isDeterministic() {
        assertThat(TossIdempotencyKeys.forBillingDelete("billing-xyz"))
                .isEqualTo(TossIdempotencyKeys.forBillingDelete("billing-xyz"));
    }

    @Test
    void forBillingApprove_isStableWithinSameCycle() {
        LocalDate cycle = LocalDate.of(2026, 9, 1);

        assertThat(TossIdempotencyKeys.forBillingApprove(5L, cycle))
                .isEqualTo(TossIdempotencyKeys.forBillingApprove(5L, cycle))
                .isEqualTo("billing-approve-5-2026-09-01");
    }

    @Test
    void forBillingApprove_differsAcrossCycles() {
        String cycle1 = TossIdempotencyKeys.forBillingApprove(5L, LocalDate.of(2026, 9, 1));
        String cycle2 = TossIdempotencyKeys.forBillingApprove(5L, LocalDate.of(2026, 10, 1));

        assertThat(cycle1).isNotEqualTo(cycle2);
    }
}
