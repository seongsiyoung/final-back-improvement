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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payment> findWithLockByOrder_Id(Long orderId);

    Optional<Payment> findByPgOrderId(String pgOrderId);

    /** 재조정 대상. 오래된 것부터 상한만큼만 가져온다. */
    @Query("SELECT p FROM Payment p "
            + "WHERE p.paymentStatus IN (:statuses) "
            + "AND p.updatedAt < :threshold "
            + "ORDER BY p.updatedAt ASC")
    List<Payment> findReconciliationTargets(
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    Page<Payment> findByPaymentStatusInOrderByUpdatedAtAsc(Collection<PaymentStatus> statuses, Pageable pageable);

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
