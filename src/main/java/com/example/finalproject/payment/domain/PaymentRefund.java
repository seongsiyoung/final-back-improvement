package com.example.finalproject.payment.domain;


import com.example.finalproject.global.domain.BaseTimeEntity;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payment_refunds",
        uniqueConstraints = @UniqueConstraint(name = "uq_refunds_store_order", columnNames = "store_order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_refunds_payment"))
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_order_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_refunds_store_order"))
    private StoreOrder storeOrder;

    @Column(name = "refund_amount")
    private Integer refundAmount;

    @Column(name = "refund_reason", length = 500)
    private String refundReason;

    private LocalDateTime refundedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_responsibility", length = 30)
    private RefundResponsibility responsibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_status", nullable = false, length = 30)
    private RefundStatus refundStatus;

    @Column(name = "is_settled", nullable = false)
    private boolean isSettled = false;

    public void markSettled() {
        this.isSettled = true;
    }

    @Builder
    public PaymentRefund(Payment payment, StoreOrder storeOrder,
                         Integer refundAmount, String refundReason,
                         RefundStatus refundStatus, boolean isSettled,
                         RefundResponsibility responsibility) {
        this.payment = payment;
        this.storeOrder = storeOrder;
        this.refundAmount = refundAmount;
        this.refundReason = refundReason;
        this.refundStatus = refundStatus != null ? refundStatus : RefundStatus.REQUESTED;
        this.responsibility = responsibility;
        this.isSettled = isSettled;
    }

    /**
     * PG 환불 확인 후 로컬 장부에 반영한다.
     * refundedAt 은 건드리지 않는다 — 환불이 확인된 시각은 markPgApproved 가 찍는다.
     */
    public void adminApprove(int refundAmount) {
        this.refundAmount = refundAmount;
        this.refundStatus = RefundStatus.APPROVED;
    }

    public void confirmRefundDetails(RefundResponsibility responsibility, int refundAmount) {
        this.responsibility = responsibility;
        this.refundAmount = refundAmount;
    }

    public void adminReject() {
        this.refundStatus = RefundStatus.REJECTED;
    }

    /**
     * PG 는 취소했고 로컬 장부 반영만 남았다.
     * refundedAt 은 여기서만 찍는다 — 기간별 환불 집계의 기준 시각이다.
     */
    public void markPgApproved() {
        this.refundStatus = RefundStatus.PG_APPROVED;
        this.refundedAt = LocalDateTime.now();
    }

    public void markPgPending() {
        if (this.refundStatus != RefundStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }
        this.refundStatus = RefundStatus.PG_PENDING;
    }

    public void markReconciliationRequired() {
        this.refundStatus = RefundStatus.RECONCILIATION_REQUIRED;
    }

    public void markPgRejected() {
        this.refundStatus = RefundStatus.PG_REJECTED;
    }

    public void revertToRequested() {
        if (this.refundStatus != RefundStatus.PG_REJECTED) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }
        this.refundStatus = RefundStatus.REQUESTED;
    }
}
