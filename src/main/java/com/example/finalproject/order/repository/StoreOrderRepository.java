package com.example.finalproject.order.repository;

import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.custom.StoreOrderRepositoryCustom;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoreOrderRepository extends JpaRepository<StoreOrder, Long>, StoreOrderRepositoryCustom {

    List<StoreOrder> findAllByOrderId(Long orderId);

    @Query("SELECT so FROM StoreOrder so JOIN FETCH so.order WHERE so.order.id IN :orderIds")
    List<StoreOrder> findAllByOrderIdIn(@Param("orderIds") List<Long> orderIds);

    List<StoreOrder> findByIdIn(List<Long> ids);

    @Query(value = "SELECT so FROM StoreOrder so JOIN FETCH so.order WHERE so.store.id = :storeId",
            countQuery = "SELECT count(so) FROM StoreOrder so WHERE so.store.id = :storeId")
    Page<StoreOrder> findAllByStoreId(@Param("storeId") Long storeId, Pageable pageable);

    @Query("SELECT so FROM StoreOrder so " +
            "JOIN FETCH so.order " +
            "WHERE so.store.id = :storeId " +
            "AND so.status IN :statuses " +
            "ORDER BY so.createdAt DESC")
    List<StoreOrder> findByStoreIdAndStatusIn(@Param("storeId") Long storeId,
                                              @Param("statuses") List<StoreOrderStatus> statuses);

    @Query("SELECT so FROM StoreOrder so " +
            "JOIN FETCH so.order " +
            "WHERE so.store.id = :storeId " +
            "AND so.status IN :statuses " +
            "ORDER BY so.updatedAt DESC")
    List<StoreOrder> findCompletedByStoreIdAndStatusIn(@Param("storeId") Long storeId,
                                                       @Param("statuses") List<StoreOrderStatus> statuses);

    @Query("SELECT so FROM StoreOrder so " +
            "JOIN FETCH so.order o " +
            "JOIN FETCH o.user " +
            "WHERE so.id = :id")
    Optional<StoreOrder> findByIdWithOrderAndUser(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select so from StoreOrder so "
            + "join fetch so.order o "
            + "join fetch o.user u "
            + "where so.id = :id")
    Optional<StoreOrder> findByIdWithLock(@Param("id") Long id);

    @Query("SELECT so FROM StoreOrder so " +
           "JOIN FETCH so.store s " +
           "JOIN FETCH s.owner " +
           "WHERE so.id = :id")
    Optional<StoreOrder> findByIdWithStoreAndOwner(@Param("id") Long id);

    List<StoreOrder> findByStatus(StoreOrderStatus status);

    List<StoreOrder> findByStatusAndCreatedAtBefore(StoreOrderStatus status, LocalDateTime createdAtBefore);

    /** 환불은 끝났는데 주문 후속 처리가 남은 건. */
    @Query("SELECT so FROM StoreOrder so "
            + "JOIN Payment p ON p.order.id = so.order.id "
            + "WHERE p.paymentStatus IN (:paymentStatuses) "
            + "AND so.status IN (:storeOrderStatuses) "
            + "AND so.updatedAt < :threshold "
            + "ORDER BY so.updatedAt ASC")
    List<StoreOrder> findRefundCompletionLostTargets(
            @Param("paymentStatuses") Collection<PaymentStatus> paymentStatuses,
            @Param("storeOrderStatuses") Collection<StoreOrderStatus> storeOrderStatuses,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    @Query(value = "SELECT so.id AS storeOrderId, o.orderNumber AS orderNumber, "
            + "so.status AS storeOrderStatus, p.paymentStatus AS paymentStatus, "
            + "so.finalPrice AS amount, so.updatedAt AS updatedAt "
            + "FROM StoreOrder so JOIN so.order o JOIN Payment p ON p.order.id = o.id "
            + "WHERE so.status IN :storeOrderStatuses "
            + "AND p.paymentStatus IN :paymentStatuses "
            + "ORDER BY so.updatedAt ASC",
            countQuery = "SELECT COUNT(so) FROM StoreOrder so JOIN Payment p ON p.order.id = so.order.id "
                    + "WHERE so.status IN :storeOrderStatuses "
                    + "AND p.paymentStatus IN :paymentStatuses")
    Page<DanglingReconciliationRow> findDanglingReconciliationRequests(
            @Param("storeOrderStatuses") Collection<StoreOrderStatus> storeOrderStatuses,
            @Param("paymentStatuses") Collection<PaymentStatus> paymentStatuses,
            Pageable pageable);

    @Query("SELECT DISTINCT p.id FROM StoreOrder so JOIN Payment p ON p.order.id = so.order.id "
            + "WHERE p.id IN :paymentIds AND so.status = :status")
    Set<Long> findPaymentIdsWithStoreOrderStatus(
            @Param("paymentIds") Collection<Long> paymentIds,
            @Param("status") StoreOrderStatus status);

    interface DanglingReconciliationRow {
        Long getStoreOrderId();

        String getOrderNumber();

        StoreOrderStatus getStoreOrderStatus();

        PaymentStatus getPaymentStatus();

        Integer getAmount();

        LocalDateTime getUpdatedAt();
    }

    // 매출 조회: 주문 유형별 DELIVERED 건수
    @Query("SELECT COUNT(so) FROM StoreOrder so "
            + "WHERE so.store.id = :storeId "
            + "AND so.status = :status "
            + "AND so.orderType = :orderType "
            + "AND so.deliveredAt BETWEEN :start AND :end")
    long countByStoreIdAndStatusAndOrderTypeAndDeliveredAtBetween(
            @Param("storeId") Long storeId,
            @Param("status") StoreOrderStatus status,
            @Param("orderType") OrderType orderType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // 매출 조회: DELIVERED 주문 finalPrice 합산
    @Query("SELECT COALESCE(SUM(so.finalPrice), 0) FROM StoreOrder so "
            + "WHERE so.store.id = :storeId "
            + "AND so.status = :status "
            + "AND so.deliveredAt BETWEEN :start AND :end")
    long sumFinalPriceByStoreIdAndStatusAndDeliveredAtBetween(
            @Param("storeId") Long storeId,
            @Param("status") StoreOrderStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    // 매출 조회: 취소/환불 대상 상태 건수
    @Query("SELECT COUNT(so) FROM StoreOrder so "
            + "WHERE so.store.id = :storeId "
            + "AND so.status IN :statuses "
            + "AND so.cancelledAt BETWEEN :start AND :end")
    long countByStoreIdAndStatusInAndCancelledAtBetween(
            @Param("storeId") Long storeId,
            @Param("statuses") List<StoreOrderStatus> statuses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT COALESCE(SUM(so.finalPrice), 0) FROM StoreOrder so "
            + "WHERE so.store.id = :storeId "
            + "AND so.status = :status "
            + "AND so.orderType = :orderType "
            + "AND so.deliveredAt BETWEEN :start AND :end")
    long sumFinalPriceByStoreIdAndStatusAndOrderTypeAndDeliveredAtBetween(
            @Param("storeId") Long storeId,
            @Param("status") StoreOrderStatus status,
            @Param("orderType") OrderType orderType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    @Query("SELECT COUNT(so) FROM StoreOrder so "
            + "WHERE so.store.id = :storeId "
            + "AND so.status = :status "
            + "AND so.deliveredAt BETWEEN :start AND :end")
    long countByStoreIdAndStatusAndDeliveredAtBetween(
            @Param("storeId") Long storeId,
            @Param("status") StoreOrderStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    List<StoreOrder> findByStoreIdAndStatusAndDeliveredAtBetween(
            Long storeId,
            StoreOrderStatus status,
            LocalDateTime start,
            LocalDateTime end
    );

    @Query("SELECT DISTINCT so.store.id FROM StoreOrder so "
            + "WHERE so.status = :status AND so.deliveredAt BETWEEN :start AND :end")
    List<Long> findDistinctStoreIdsByStatusAndDeliveredAtBetween(
            @Param("status") StoreOrderStatus status,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @EntityGraph(attributePaths = {"store", "store.storeCategory", "order", "order.user"})
    @Query(value = "SELECT so FROM StoreOrder so "
            + "JOIN so.store s "
            + "JOIN so.order o "
            + "WHERE o.orderedAt >= :start "
            + "AND o.orderedAt < :end "
            + "AND (:keyword = '' "
            + "OR LOWER(s.storeName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) "
            + "OR LOWER(o.user.name) LIKE LOWER(CONCAT('%', :keyword, '%'))) "
            + "ORDER BY o.orderedAt DESC, so.id DESC",
            countQuery = "SELECT COUNT(so) FROM StoreOrder so "
                    + "JOIN so.store s "
                    + "JOIN so.order o "
                    + "WHERE o.orderedAt >= :start "
                    + "AND o.orderedAt < :end "
                    + "AND (:keyword = '' "
                    + "OR LOWER(s.storeName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
                    + "OR LOWER(o.orderNumber) LIKE LOWER(CONCAT('%', :keyword, '%')) "
                    + "OR LOWER(o.user.name) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<StoreOrder> searchForAdminPayments(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("SELECT COALESCE(SUM(so.finalPrice), 0) FROM StoreOrder so "
            + "WHERE so.order.orderedAt BETWEEN :start AND :end")
    long sumFinalPriceByOrderOrderedAtBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    long countByOrder_OrderedAtBetween(LocalDateTime start, LocalDateTime end);

    List<StoreOrder> findByOrder_OrderedAtBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT MAX(so.order.orderedAt) FROM StoreOrder so WHERE so.order.orderedAt IS NOT NULL")
    LocalDateTime findMaxOrderOrderedAt();

    @Query("SELECT COALESCE(SUM(so.finalPrice), 0) FROM StoreOrder so "
            + "WHERE so.order.orderType = :orderType "
            + "AND so.order.orderedAt BETWEEN :start AND :end")
    long sumFinalPriceByOrderTypeAndOrderedAtBetween(
            @Param("orderType") OrderType orderType,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    // 정산 상세 조회: settlement_id 기준 StoreOrder + Order 조인
    @Query("SELECT so FROM StoreOrder so "
            + "JOIN FETCH so.order o "
            + "WHERE so.settlement.id = :settlementId "
            + "ORDER BY so.id DESC")
    List<StoreOrder> findAllBySettlementIdWithOrder(@Param("settlementId") Long settlementId);
}
