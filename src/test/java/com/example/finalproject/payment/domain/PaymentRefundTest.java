package com.example.finalproject.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("REQUESTED 에서만 PG_PENDING 으로 간다")
    void markPgPending_fromRequested() {
        PaymentRefund refund = refundWithStatus(RefundStatus.REQUESTED);

        refund.markPgPending();

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("이미 PG_PENDING 이면 다시 찍지 않는다")
    void markPgPending_fromPgPending_throws() {
        PaymentRefund refund = refundWithStatus(RefundStatus.PG_PENDING);

        assertThatThrownBy(refund::markPgPending)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REFUND_STATUS);
    }

    @Test
    @DisplayName("RECONCILIATION_REQUIRED 는 어느 상태에서든 기록할 수 있다")
    void markReconciliationRequired_hasNoGuard() {
        PaymentRefund refund = refundWithStatus(RefundStatus.PG_APPROVED);

        refund.markReconciliationRequired();

        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("생성 직후에는 환불 확인 시각이 없다 — 요청 시각은 createdAt 이다")
    void newRefund_hasNoRefundedAt() {
        PaymentRefund refund = requestedRefund();

        assertThat(refund.getRefundedAt()).isNull();
    }

    @Test
    @DisplayName("PG 환불이 확인되면 그때 환불 시각을 찍는다")
    void markPgApproved_stampsRefundedAt() {
        PaymentRefund refund = refundWithStatus(RefundStatus.PG_PENDING);

        refund.markPgApproved();

        assertThat(refund.getRefundedAt()).isNotNull();
    }

    @Test
    @DisplayName("장부 반영은 환불 확인 시각을 덮어쓰지 않는다")
    void adminApprove_doesNotOverwriteRefundedAt() {
        PaymentRefund refund = refundWithStatus(RefundStatus.PG_PENDING);
        refund.markPgApproved();
        LocalDateTime stampedAt = refund.getRefundedAt();

        refund.adminApprove(3000);

        assertThat(refund.getRefundedAt()).isEqualTo(stampedAt);
        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
        assertThat(refund.getRefundAmount()).isEqualTo(3000);
    }

    @Test
    @DisplayName("거절된 환불에는 환불 시각이 남지 않는다")
    void rejectedRefund_hasNoRefundedAt() {
        PaymentRefund refund = requestedRefund();

        refund.adminReject();

        assertThat(refund.getRefundedAt()).isNull();
    }
}
