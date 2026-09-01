package com.example.finalproject.payment.domain;

import com.example.finalproject.global.domain.BaseTimeEntity;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.subscription.domain.Subscription;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "subscription_payments",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_sub_payments_order_id", columnNames = "pg_order_id"),
                @UniqueConstraint(name = "uq_sub_payments_payment_key", columnNames = "payment_key")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubscriptionPayment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_sub_payments_subscription"))
    private Subscription subscription;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
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

    @Column(name = "pg_order_id", nullable = false, unique = true, length = 64)
    private String pgOrderId;

    @Column(name = "card_company", length = 50)
    private String cardCompany;

    @Column(name = "card_number_masked", length = 30)
    private String cardNumberMasked;

    private LocalDateTime paidAt;

    @Column(name = "billing_cycle_date")
    private LocalDate billingCycleDate;

    @Builder
    public SubscriptionPayment(Subscription subscription,
                               PaymentMethodType paymentMethod,
                               PaymentStatus paymentStatus,
                               Integer amount,
                               String pgOrderId,
                               String pgProvider,
                               LocalDate billingCycleDate) {
        this.subscription = subscription;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus != null ? paymentStatus : PaymentStatus.PENDING;
        this.amount = amount;
        this.pgOrderId = pgOrderId;
        this.pgProvider = pgProvider;
        this.billingCycleDate = billingCycleDate;
    }

    public void approve(String paymentKey, String pgTransactionId,
                        String cardCompany, String cardNumberMasked) {
        this.paymentKey = paymentKey;
        this.pgTransactionId = pgTransactionId;
        this.cardCompany = cardCompany;
        this.cardNumberMasked = cardNumberMasked;
        this.paymentStatus = PaymentStatus.APPROVED;
        this.paidAt = LocalDateTime.now();
    }

    public void fail() {
        this.paymentStatus = PaymentStatus.FAILED;
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
}
