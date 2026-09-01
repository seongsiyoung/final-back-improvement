package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefund, Long> {

    /** 아직 종결되지 않은 환불 상태. 활성 건이 하나임을 앱 가드가 지킨다. */
    List<RefundStatus> ACTIVE_REFUND_STATUSES = List.of(
            RefundStatus.REQUESTED,
            RefundStatus.PG_PENDING,
            RefundStatus.PG_APPROVED,
            RefundStatus.RECONCILIATION_REQUIRED);

    List<PaymentRefund> findByStoreOrderIdOrderByCreatedAtDesc(Long storeOrderId);

    @Query(value = "SELECT r FROM PaymentRefund r " +
            "JOIN FETCH r.storeOrder so " +
            "JOIN FETCH so.store s " +
            "JOIN FETCH so.order o " +
            "JOIN FETCH o.user u " +
            "WHERE (:status IS NULL OR r.refundStatus = :status)",
            countQuery = "SELECT COUNT(r) FROM PaymentRefund r "
                    + "WHERE (:status IS NULL OR r.refundStatus = :status)")
    Page<PaymentRefund> findAdminRefundsWithDetails(@Param("status") RefundStatus status, Pageable pageable);

    @Query("SELECT r FROM PaymentRefund r " +
            "JOIN FETCH r.payment p " +
            "JOIN FETCH r.storeOrder so " +
            "JOIN FETCH so.store s " +
            "JOIN FETCH so.order o " +
            "JOIN FETCH o.user u " +
            "WHERE r.id = :refundId")
    Optional<PaymentRefund> findAdminRefundDetailById(@Param("refundId") Long refundId);

    @Query("SELECT COALESCE(SUM(pr.refundAmount), 0 ) "
            + "FROM PaymentRefund pr "
            + "WHERE pr.payment.id = :paymentId")
    int sumRefundAmountByPaymentId(@Param("paymentId") Long paymentId);

    @Query("SELECT COALESCE(SUM(pr.refundAmount), 0) "
            + "FROM PaymentRefund pr "
            + "WHERE pr.storeOrder.store.id = :storeId "
            + "AND pr.refundedAt BETWEEN :start AND :end")
    long sumRefundAmountByStoreOrderStoreIdAndRefundedAtBetween(
            @Param("storeId") Long storeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 상점 기준 환불 금액 (배달비 제외, storeProductPrice만 합산) - 매출 조회용
     */
    @Query("SELECT COALESCE(SUM(pr.storeOrder.storeProductPrice), 0L) "
            + "FROM PaymentRefund pr "
            + "WHERE pr.storeOrder.store.id = :storeId "
            + "AND pr.refundedAt BETWEEN :start AND :end")
    long sumStoreProductPriceByStoreOrderStoreIdAndRefundedAtBetween(
            @Param("storeId") Long storeId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 아직 종결되지 않은 환불 한 건을 찾는다.
     *
     * <p>PG_REJECTED 와 REJECTED 는 종결로 보고 제외한다. 관리자가 다시 시도하면
     * 그 이력을 덮어쓰지 않고 새 행을 만든다.
     *
     * <p>Optional 이 성립하는 근거는 PaymentCommandService.startRefund 가 Payment 행에
     * 비관적 락을 잡기 때문이다. 활성 건 검사와 생성이 그 락 안에서 직렬화된다.
     */
    @Query("SELECT pr FROM PaymentRefund pr "
            + "WHERE pr.storeOrder.id = :storeOrderId "
            + "AND pr.refundStatus IN (:activeStatuses)")
    Optional<PaymentRefund> findActiveByStoreOrderId(
            @Param("storeOrderId") Long storeOrderId,
            @Param("activeStatuses") Collection<RefundStatus> activeStatuses);

    default Optional<PaymentRefund> findActiveByStoreOrderId(Long storeOrderId) {
        return findActiveByStoreOrderId(storeOrderId, ACTIVE_REFUND_STATUSES);
    }

    @Query("SELECT pr.storeOrder.id, COALESCE(SUM(pr.refundAmount), 0) "
            + "FROM PaymentRefund pr "
            + "WHERE pr.storeOrder.id IN :storeOrderIds "
            + "GROUP BY pr.storeOrder.id")
    List<Object[]> sumRefundAmountGroupByStoreOrderId(@Param("storeOrderIds") List<Long> storeOrderIds);

    @Query("SELECT COALESCE(SUM(pr.refundAmount), 0) "
            + "FROM PaymentRefund pr "
            + "WHERE pr.storeOrder.store.id = :storeId "
            + "AND pr.refundedAt > :startExclusive "
            + "AND pr.refundedAt <= :endInclusive "
            + "AND (pr.refundStatus = :approvedStatus OR pr.refundStatus IS NULL) "
            + "AND (pr.responsibility = :storeResponsibility OR pr.responsibility IS NULL)")
    long sumApprovedStoreRefundAmountByStoreIdAndCutoffBetween(
            @Param("storeId") Long storeId,
            @Param("startExclusive") LocalDateTime startExclusive,
            @Param("endInclusive") LocalDateTime endInclusive,
            @Param("approvedStatus") RefundStatus approvedStatus,
            @Param("storeResponsibility") RefundResponsibility storeResponsibility);

    long countByRefundStatus(RefundStatus refundStatus);

    @Query("SELECT COALESCE(SUM(pr.refundAmount), 0) "
            + "FROM PaymentRefund pr "
            + "WHERE pr.refundStatus = :refundStatus "
            + "AND pr.refundedAt BETWEEN :start AND :end")
    long sumRefundAmountByRefundStatusAndRefundedAtBetween(
            @Param("refundStatus") RefundStatus refundStatus,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
