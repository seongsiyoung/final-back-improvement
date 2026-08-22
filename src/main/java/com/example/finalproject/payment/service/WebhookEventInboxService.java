package com.example.finalproject.payment.service;

import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.event.WebhookEventReceivedEvent;
import com.example.finalproject.payment.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 인박스 저장 전담. "먼저 존재 여부를 확인해 재전송을 피하고, 그래도 경합하면
 * DB UNIQUE 제약을 최종 방어선으로 둔다"는 이 저장소의 기존 원칙(3단계 설계 문서)을 따른다.
 * 같은 트랜잭션 안에서 제약 위반을 잡고 계속 진행하지 않는다 — PostgreSQL은 제약 위반이
 * 발생한 트랜잭션 전체를 abort 상태로 만들어 이후 문장을 전부 실패시키기 때문이다.
 * 그래서 제약 위반 예외는 이 메서드 밖(WebhookController)에서 잡는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventInboxService {

    private final WebhookEventRepository webhookEventRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public void receive(String transmissionId, String transmissionTime, String eventType,
                         String orderId, String payload) {
        if (webhookEventRepository.existsByTransmissionId(transmissionId)) {
            log.info("이미 수신한 웹훅 재전송이라 무시함. transmissionId={}", transmissionId);
            return;
        }

        WebhookEvent event = webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId(transmissionId)
                        .transmissionTime(transmissionTime)
                        .eventType(eventType)
                        .orderId(orderId)
                        .payload(payload)
                        .build());

        applicationEventPublisher.publishEvent(new WebhookEventReceivedEvent(event.getId()));
    }
}
