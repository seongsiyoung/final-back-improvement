package com.example.finalproject.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import org.junit.jupiter.api.Test;

class PaymentRefundTest {

    private PaymentRefund requestedRefund() {
        return PaymentRefund.builder()
                .refundAmount(1000)
                .refundStatus(RefundStatus.REQUESTED)
                .responsibility(RefundResponsibility.PLATFORM)
                .isSettled(false)
                .build();
    }

    @Test
    void markPgRejected_fromRequested_setsStatusToPgRejected() {
        PaymentRefund refund = requestedRefund();

        refund.markPgRejected();

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.PG_REJECTED);
    }

    @Test
    void revertToRequested_fromPgRejected_setsStatusToRequested() {
        PaymentRefund refund = requestedRefund();
        refund.markPgRejected();

        refund.revertToRequested();

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    void revertToRequested_whenNotPgRejected_throws() {
        PaymentRefund refund = requestedRefund();

        assertThatThrownBy(refund::revertToRequested)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void adminApprove_setsApprovedStatusAndAmount_withoutDeadSelfAssignment() {
        PaymentRefund refund = requestedRefund();

        refund.adminApprove(2000);

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(refund.getRefundAmount()).isEqualTo(2000);
    }
}
