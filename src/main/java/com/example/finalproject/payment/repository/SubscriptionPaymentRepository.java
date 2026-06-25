package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.enums.PaymentStatus;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionPaymentRepository extends JpaRepository<SubscriptionPayment, Long> {

    boolean existsBySubscription_IdAndBillingCycleDateAndPaymentStatus(
            Long subscriptionId, LocalDate billingCycleDate, PaymentStatus paymentStatus);
}