package com.example.finalproject.admin.service;

import com.example.finalproject.admin.dto.reconciliation.AdminActionRequiredListResponse;
import com.example.finalproject.admin.dto.reconciliation.AdminAutoRecoveryItemResponse;
import com.example.finalproject.admin.dto.reconciliation.AdminAutoRecoveryWaitingResponse;
import com.example.finalproject.admin.dto.reconciliation.AdminReconciliationItemResponse;
import com.example.finalproject.admin.dto.reconciliation.AdminReconciliationSummaryResponse;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.SubscriptionPaymentRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminReconciliationService {

    private static final List<PaymentStatus> ACTION_PAYMENT_STATUSES =
            List.of(PaymentStatus.RECONCILIATION_REQUIRED);
    private static final List<PaymentStatus> AUTO_PAYMENT_STATUSES = List.of(
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.REFUND_REQUESTED);
    private static final List<RefundStatus> AUTO_REFUND_STATUSES =
            List.of(RefundStatus.PG_PENDING, RefundStatus.PG_APPROVED);
    private static final List<StoreOrderStatus> DANGLING_STORE_ORDER_STATUSES = List.of(
            StoreOrderStatus.CANCEL_REQUESTED,
            StoreOrderStatus.REJECT_REQUESTED,
            StoreOrderStatus.REFUND_REQUESTED);
    private static final List<PaymentStatus> DANGLING_PAYMENT_STATUSES =
            List.of(PaymentStatus.APPROVED, PaymentStatus.PARTIAL_REFUNDED);
    private static final List<String> PAYMENT_OUTCOMES = List.of("NOT_CHARGED", "REFUNDED");
    private static final List<String> REFUND_OUTCOMES = List.of("REFUNDED", "NOT_REFUNDED");

    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionPaymentRepository subscriptionPaymentRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final StoreOrderRepository storeOrderRepository;

    public AdminActionRequiredListResponse getActionRequired(String adminEmail, Pageable pageable) {
        validateAdmin(adminEmail);

        Page<Payment> payments = paymentRepository.findActionRequired(
                ACTION_PAYMENT_STATUSES,
                PaymentRefundRepository.ACTIVE_REFUND_STATUSES,
                pageable);
        Set<Long> rejectedOrderPaymentIds = findRejectedOrderPaymentIds(payments);

        return new AdminActionRequiredListResponse(
                payments.map(payment -> toPaymentItem(
                        payment, rejectedOrderPaymentIds.contains(payment.getId()))),
                subscriptionPaymentRepository.findActionRequired(ACTION_PAYMENT_STATUSES, pageable)
                        .map(this::toSubscriptionPaymentItem),
                paymentRefundRepository.findActionRequired(
                                RefundStatus.RECONCILIATION_REQUIRED,
                                RefundStatus.PG_REJECTED,
                                StoreOrderStatus.DELIVERED,
                                pageable)
                        .map(this::toRefundItem),
                storeOrderRepository.findDanglingReconciliationRequests(
                                DANGLING_STORE_ORDER_STATUSES,
                                DANGLING_PAYMENT_STATUSES,
                                pageable)
                        .map(this::toDanglingRequestItem));
    }

    private Set<Long> findRejectedOrderPaymentIds(Page<Payment> payments) {
        List<Long> paymentIds = payments.getContent().stream().map(Payment::getId).toList();
        if (paymentIds.isEmpty()) {
            return Set.of();
        }
        return storeOrderRepository.findPaymentIdsWithStoreOrderStatus(
                paymentIds, StoreOrderStatus.REJECT_REQUESTED);
    }

    public AdminAutoRecoveryWaitingResponse getAutoRecoveryWaiting(String adminEmail, Pageable pageable) {
        validateAdmin(adminEmail);

        return new AdminAutoRecoveryWaitingResponse(
                buildAutoRecoverySummary(),
                paymentRepository.findByPaymentStatusInOrderByUpdatedAtAsc(AUTO_PAYMENT_STATUSES, pageable)
                        .map(payment -> toAutoRecoveryItem(
                                payment.getId(), payment.getPaymentStatus().name(), payment.getUpdatedAt())),
                subscriptionPaymentRepository
                        .findByPaymentStatusInOrderByUpdatedAtAsc(AUTO_PAYMENT_STATUSES, pageable)
                        .map(payment -> toAutoRecoveryItem(
                                payment.getId(), payment.getPaymentStatus().name(), payment.getUpdatedAt())),
                paymentRefundRepository.findByRefundStatusInOrderByUpdatedAtAsc(AUTO_REFUND_STATUSES, pageable)
                        .map(refund -> toAutoRecoveryItem(
                                refund.getId(), refund.getRefundStatus().name(), refund.getUpdatedAt())));
    }

    private AdminReconciliationSummaryResponse buildAutoRecoverySummary() {
        List<Object[]> rows = new ArrayList<>();
        rows.addAll(paymentRepository.countByStatusGroup(AUTO_PAYMENT_STATUSES));
        rows.addAll(subscriptionPaymentRepository.countByStatusGroup(AUTO_PAYMENT_STATUSES));
        rows.addAll(paymentRefundRepository.countByStatusGroup(AUTO_REFUND_STATUSES));

        Map<String, Long> countsByStatus = new TreeMap<>();
        long totalCount = 0;
        LocalDateTime oldestUpdatedAt = null;
        for (Object[] row : rows) {
            String status = row[0].toString();
            long count = ((Number) row[1]).longValue();
            LocalDateTime statusOldestUpdatedAt = (LocalDateTime) row[2];

            countsByStatus.merge(status, count, Long::sum);
            totalCount += count;
            if (oldestUpdatedAt == null || statusOldestUpdatedAt.isBefore(oldestUpdatedAt)) {
                oldestUpdatedAt = statusOldestUpdatedAt;
            }
        }

        return new AdminReconciliationSummaryResponse(
                totalCount,
                oldestUpdatedAt,
                new LinkedHashMap<>(countsByStatus));
    }

    private AdminReconciliationItemResponse toPaymentItem(Payment payment, boolean rejectedOrder) {
        return AdminReconciliationItemResponse.payment(
                payment.getId(),
                payment.getPaymentStatus().name(),
                payment.getUpdatedAt(),
                rejectedOrder ? "INVESTIGATE" : "RESOLVE",
                rejectedOrder ? List.of() : PAYMENT_OUTCOMES,
                !rejectedOrder,
                payment.getPgOrderId(),
                payment.getAmount(),
                payment.getRefundedAmount(),
                payment.getOrder().getOrderNumber());
    }

    private AdminReconciliationItemResponse toSubscriptionPaymentItem(SubscriptionPayment payment) {
        return AdminReconciliationItemResponse.subscriptionPayment(
                payment.getId(),
                payment.getPaymentStatus().name(),
                payment.getUpdatedAt(),
                PAYMENT_OUTCOMES,
                payment.getPgOrderId(),
                payment.getAmount(),
                payment.getSubscription().getId(),
                payment.getBillingCycleDate());
    }

    private AdminReconciliationItemResponse toRefundItem(PaymentRefund refund) {
        boolean retry = refund.getRefundStatus() == RefundStatus.PG_REJECTED;
        return AdminReconciliationItemResponse.refund(
                refund.getId(),
                refund.getRefundStatus().name(),
                refund.getUpdatedAt(),
                retry ? "RETRY" : "RESOLVE",
                retry ? List.of() : REFUND_OUTCOMES,
                !retry,
                refund.getPayment().getPgOrderId(),
                refund.getRefundAmount(),
                refund.getRefundReason(),
                refund.getStoreOrder().getId(),
                refund.getStoreOrder().getOrder().getOrderNumber());
    }

    private AdminReconciliationItemResponse toDanglingRequestItem(
            StoreOrderRepository.DanglingReconciliationRow row) {
        return AdminReconciliationItemResponse.danglingRequest(
                row.getStoreOrderId(),
                row.getUpdatedAt(),
                row.getOrderNumber(),
                row.getStoreOrderStatus().name(),
                row.getPaymentStatus().name(),
                row.getAmount());
    }

    private AdminAutoRecoveryItemResponse toAutoRecoveryItem(
            Long id, String status, LocalDateTime updatedAt) {
        return new AdminAutoRecoveryItemResponse(id, status, updatedAt);
    }

    private User validateAdmin(String adminEmail) {
        User admin = userRepository.findByEmailAndDeletedAtIsNull(adminEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_AUTHORITY_REQUIRED));
        if (!admin.isAdmin()) {
            throw new BusinessException(ErrorCode.ADMIN_AUTHORITY_REQUIRED);
        }
        return admin;
    }
}
