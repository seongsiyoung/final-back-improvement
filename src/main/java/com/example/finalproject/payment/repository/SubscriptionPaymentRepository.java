package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
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
}
