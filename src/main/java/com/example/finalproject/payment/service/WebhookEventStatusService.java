package com.example.finalproject.payment.service;

import com.example.finalproject.payment.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WebhookEvent.status 확정 전담. WebhookEventProcessor와 별도 빈으로 분리한 이유는
 * 이 저장소가 이미 쓰는 패턴(PaymentConfirmCommandService 등)과 같다 — 같은 클래스
 * 안에서 this로 자기 자신의 @Transactional 메서드를 호출하면(self-invocation) 프록시를
 * 안 거쳐서 트랜잭션이 적용되지 않는다. 별도 빈으로 분리하면 이 함정 자체가 성립하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class WebhookEventStatusService {

    private final WebhookEventRepository webhookEventRepository;

    @Transactional
    public void markProcessed(Long webhookEventId) {
        webhookEventRepository.findById(webhookEventId).ifPresent(event -> event.markProcessed());
    }

    @Transactional
    public void markFailed(Long webhookEventId) {
        webhookEventRepository.findById(webhookEventId).ifPresent(event -> event.markFailed());
    }
}
