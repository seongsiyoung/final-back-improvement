package com.example.finalproject.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentRefundTest {

    private PaymentRefund refundWithStatus(RefundStatus status) {
        return PaymentRefund.builder()
                .refundAmount(1000)
                .refundStatus(status)
                .responsibility(RefundResponsibility.PLATFORM)
                .isSettled(false)
                .build();
    }

    private PaymentRefund requestedRefund() {
        return refundWithStatus(RefundStatus.REQUESTED);
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

    @ParameterizedTest
    @EnumSource(value = RefundStatus.class, names = "PG_REJECTED", mode = EnumSource.Mode.EXCLUDE)
    void revertToRequested_whenNotPgRejected_throwsBusinessExceptionWithInvalidRefundStatus(RefundStatus status) {
        PaymentRefund refund = refundWithStatus(status);

        assertThatThrownBy(refund::revertToRequested)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REFUND_STATUS));
    }

    @Test
    void revertToRequested_thenMarkPgRejectedAgain_allowsRepeatRetryCycle() {
        PaymentRefund refund = requestedRefund();

        refund.markPgRejected();
        refund.revertToRequested();
        refund.markPgRejected();

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.PG_REJECTED);
    }

    @Test
    void adminApprove_setsApprovedStatusAndAmount_withoutDeadSelfAssignment() {
        PaymentRefund refund = requestedRefund();

        refund.adminApprove(2000);

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(refund.getRefundAmount()).isEqualTo(2000);
    }
}
