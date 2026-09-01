package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import java.time.LocalDate;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    boolean existsBySubscription_IdAndBillingCycleDateAndPaymentStatusIn(
            Long subscriptionId, LocalDate billingCycleDate, Collection<PaymentStatus> paymentStatuses);
}
