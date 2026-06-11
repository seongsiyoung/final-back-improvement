package com.example.finalproject.payment.repository;

import com.example.finalproject.payment.domain.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    boolean existsByTransmissionId(String transmissionId);
}
