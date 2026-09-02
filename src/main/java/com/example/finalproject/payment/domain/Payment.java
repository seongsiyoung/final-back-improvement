package com.example.finalproject.payment.domain;


import com.example.finalproject.global.domain.BaseTimeEntity;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false, unique = true,
            foreignKey = @ForeignKey(name = "fk_payments_order"))
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethodType paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.PENDING;

    @Column(nullable = false)
    private Integer amount;

    @Column(name = "pg_provider", length = 50)
    private String pgProvider;

    @Column(name = "pg_transaction_id", length = 100)
    private String pgTransactionId;

    @Column(name = "payment_key", unique = true, length = 200)
    private String paymentKey;

    @Column(name = "pg_order_id", nullable = false, unique = true, length = 50)
    private String pgOrderId;

    @Column(name = "card_company", length = 50)
    private String cardCompany;

    @Column(name = "card_number_masked", length = 30)
    private String cardNumberMasked;

    @Column(name = "receipt_url", length = 500)
    private String receiptUrl;

    private LocalDateTime paidAt;

    private Integer refundedAmount = 0;

    @Builder
    public Payment(Order order, PaymentStatus paymentStatus, PaymentMethodType paymentMethod, Integer amount,
                   String pgOrderId, String pgProvider) {
        this.order = order;
        this.paymentStatus = paymentStatus != null ? paymentStatus : PaymentStatus.PENDING;
        this.paymentMethod = paymentMethod;
        this.amount = amount;
        this.pgOrderId = pgOrderId;
        this.pgProvider = pgProvider;
    }

    public void approve(String paymentKey,
                        String pgTransactionId,
                        String receiptUrl) {

        this.paymentKey = paymentKey;
        this.pgTransactionId = pgTransactionId;
        this.receiptUrl = receiptUrl;
        this.paymentStatus = PaymentStatus.APPROVED;
        this.paidAt = LocalDateTime.now();
    }

    public void markPending() {
        if (this.paymentStatus != PaymentStatus.READY) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }

        this.paymentStatus = PaymentStatus.PENDING;
    }

    public void fail() {
        this.paymentStatus = PaymentStatus.FAILED;
    }

    public void revertToReady() {
        this.paymentStatus = PaymentStatus.READY;
    }

    public void Refunded() {
        this.refundedAmount = this.amount;
        this.paymentStatus = PaymentStatus.REFUNDED;
    }

    public void applyCumulativeCanceledAmount(int cumulativeCanceledAmount) {
        this.refundedAmount = cumulativeCanceledAmount;

        if (this.refundedAmount >= this.amount) {
            this.paymentStatus = PaymentStatus.REFUNDED;
        } else if (this.refundedAmount > 0) {
            this.paymentStatus = PaymentStatus.PARTIAL_REFUNDED;
        } else {
            this.paymentStatus = PaymentStatus.APPROVED;
        }
    }

    public boolean isFullyRefunded() {
        return this.refundedAmount != null && this.refundedAmount >= this.amount;
    }

    public void markRefundRequested() {
        if (this.paymentStatus != PaymentStatus.PENDING &&
                this.paymentStatus != PaymentStatus.APPROVED &&
                this.paymentStatus != PaymentStatus.PARTIAL_REFUNDED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }

        this.paymentStatus = PaymentStatus.REFUND_REQUESTED;
    }

    public void markReversalPending() {
        if (this.paymentStatus != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }
        this.paymentStatus = PaymentStatus.REVERSAL_PENDING;
    }

    public void markReconciliationRequired() {
        this.paymentStatus = PaymentStatus.RECONCILIATION_REQUIRED;
    }

    public void resolveReconciliationAsRefunded(int cumulativeCanceledAmount) {
        validateReconciliationRequired();
        applyCumulativeCanceledAmount(cumulativeCanceledAmount);
    }

    public void resolveReconciliationAsNotRefunded() {
        validateReconciliationRequired();
        this.paymentStatus = (this.refundedAmount == null || this.refundedAmount == 0)
                ? PaymentStatus.APPROVED
                : PaymentStatus.PARTIAL_REFUNDED;
    }

    private void validateReconciliationRequired() {
        if (this.paymentStatus != PaymentStatus.RECONCILIATION_REQUIRED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }
    }

    public void revertRefundRequest() {
        if (this.paymentStatus != PaymentStatus.REFUND_REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_CANCEL_STATUS);
        }
        this.paymentStatus = (this.refundedAmount == null || this.refundedAmount == 0)
                ? PaymentStatus.APPROVED
                : PaymentStatus.PARTIAL_REFUNDED;
    }
}
