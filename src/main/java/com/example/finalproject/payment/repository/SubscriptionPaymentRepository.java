package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    boolean existsBySubscription_IdAndBillingCycleDateAndPaymentStatusIn(
            Long subscriptionId, LocalDate billingCycleDate, Collection<PaymentStatus> paymentStatuses);

    boolean existsBySubscription_UserIdAndPaymentStatusIn(
            Long userId, Collection<PaymentStatus> paymentStatuses);

    @Query("SELECT sp FROM SubscriptionPayment sp "
            + "WHERE sp.paymentStatus IN (:statuses) "
            + "AND sp.updatedAt < :threshold "
            + "ORDER BY sp.updatedAt ASC")
    List<SubscriptionPayment> findReconciliationTargets(
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable);

    Page<SubscriptionPayment> findByPaymentStatusInOrderByUpdatedAtAsc(Collection<PaymentStatus> statuses, Pageable pageable);

    @EntityGraph(attributePaths = "subscription")
    @Query(value = "SELECT sp FROM SubscriptionPayment sp "
            + "WHERE sp.paymentStatus IN :statuses ORDER BY sp.updatedAt ASC",
            countQuery = "SELECT COUNT(sp) FROM SubscriptionPayment sp WHERE sp.paymentStatus IN :statuses")
    Page<SubscriptionPayment> findActionRequired(
            @Param("statuses") Collection<PaymentStatus> statuses,
            Pageable pageable);

    @Query("SELECT sp.paymentStatus, COUNT(sp), MIN(sp.updatedAt) FROM SubscriptionPayment sp WHERE sp.paymentStatus IN :statuses GROUP BY sp.paymentStatus")
    List<Object[]> countByStatusGroup(@Param("statuses") Collection<PaymentStatus> statuses);
}
