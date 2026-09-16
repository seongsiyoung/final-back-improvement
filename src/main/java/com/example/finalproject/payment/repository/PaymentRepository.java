package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrder_Id(Long orderId);

    long countByOrder_UserIdAndPaymentStatusIn(Long userId, Collection<PaymentStatus> paymentStatuses);

    long countByOrder_UserIdAndPaymentStatusInAndPaidAtIsNull(
            Long userId, Collection<PaymentStatus> paymentStatuses);

    List<Payment> findByOrder_IdIn(List<Long> orderIds);

    long countByPaymentStatusInAndPaidAtBetween(
            Collection<PaymentStatus> statuses,
            LocalDateTime start,
            LocalDateTime end
    );

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
            + "WHERE p.paymentStatus IN :statuses "
            + "AND p.paidAt BETWEEN :start AND :end")
    long sumAmountByPaymentStatusInAndPaidAtBetween(
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query("SELECT COALESCE(SUM(p.refundedAmount), 0) FROM Payment p "
            + "WHERE p.refundedAmount IS NOT NULL "
            + "AND p.paidAt BETWEEN :start AND :end")
    long sumRefundedAmountByPaidAtBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockById(Long paymentId);

    /** 파생 쿼리로 쓰면 orders 를 outer join 해 락이 붙지 않는다. FK 컬럼으로 직접 건다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.order.id = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") Long orderId);

    /**
     * 조인 대신 서브쿼리를 쓴다. 조인하면 잠금이 payments 와 orders 에 함께 걸리고,
     * 파생 쿼리로 쓰면 outer join 이 생겨 잠금이 아예 붙지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p "
            + "where p.order.id in (select o.id from Order o where o.user.id = :userId) "
            + "and p.paymentStatus in :statuses")
    List<Payment> lockByUserIdAndStatuses(
            @Param("userId") Long userId,
            @Param("statuses") Collection<PaymentStatus> statuses);

    Optional<Payment> findByPgOrderId(String pgOrderId);

    @Query("SELECT p FROM Payment p "
            + "WHERE p.paymentStatus IN (:statuses) "
            + "AND p.updatedAt < :threshold "
            + "ORDER BY p.lastReconciledAt ASC NULLS FIRST")
    List<Payment> findReconciliationTargets(
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    @Query("SELECT p FROM Payment p "
            + "WHERE p.paymentStatus = :status "
            + "AND p.createdAt < :threshold "
            + "ORDER BY p.createdAt ASC")
    List<Payment> findExpiredReadyPayments(
            @Param("status") PaymentStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    Page<Payment> findByPaymentStatusInOrderByUpdatedAtAsc(Collection<PaymentStatus> statuses, Pageable pageable);

    long countByPaymentStatusInAndReconcileAttemptsGreaterThanEqual(
            Collection<PaymentStatus> statuses, int reconcileAttempts);

    @EntityGraph(attributePaths = "order")
    @Query(value = "SELECT p FROM Payment p "
            + "WHERE p.paymentStatus IN :statuses "
            + "AND NOT EXISTS (SELECT pr.id FROM PaymentRefund pr "
            + "WHERE pr.payment = p AND pr.refundStatus IN :activeRefundStatuses) "
            + "ORDER BY p.updatedAt ASC",
            countQuery = "SELECT COUNT(p) FROM Payment p "
                    + "WHERE p.paymentStatus IN :statuses "
                    + "AND NOT EXISTS (SELECT pr.id FROM PaymentRefund pr "
                    + "WHERE pr.payment = p AND pr.refundStatus IN :activeRefundStatuses)")
    Page<Payment> findActionRequired(
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("activeRefundStatuses") Collection<RefundStatus> activeRefundStatuses,
            Pageable pageable);

    @Query("SELECT p.paymentStatus, COUNT(p), MIN(p.updatedAt) FROM Payment p WHERE p.paymentStatus IN :statuses GROUP BY p.paymentStatus")
    List<Object[]> countByStatusGroup(@Param("statuses") Collection<PaymentStatus> statuses);
}
