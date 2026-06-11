package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.event.WebhookEventReceivedEvent;
import com.example.finalproject.payment.repository.WebhookEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class WebhookEventInboxServiceTest {

    private WebhookEventRepository webhookEventRepository;
    private ApplicationEventPublisher applicationEventPublisher;
    private WebhookEventInboxService webhookEventInboxService;

    @BeforeEach
    void setUp() {
        webhookEventRepository = mock(WebhookEventRepository.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        webhookEventInboxService = new WebhookEventInboxService(webhookEventRepository, applicationEventPublisher);
    }

    @Test
    void receive_whenAlreadyExists_doesNotSaveOrPublish() {
        when(webhookEventRepository.existsByTransmissionId("tx-1")).thenReturn(true);

        webhookEventInboxService.receive("tx-1", "2026-08-21T00:00:00+09:00",
                "PAYMENT_STATUS_CHANGED", "order-1", "{}");

        verify(webhookEventRepository, never()).save(any());
        verify(applicationEventPublisher, never()).publishEvent(any());
    }

    @Test
    void receive_whenNew_savesThenPublishes() {
        when(webhookEventRepository.existsByTransmissionId("tx-2")).thenReturn(false);
        WebhookEvent saved = WebhookEvent.builder()
                .transmissionId("tx-2")
                .transmissionTime("2026-08-21T00:00:00+09:00")
                .eventType("PAYMENT_STATUS_CHANGED")
                .orderId("order-2")
                .payload("{}")
                .build();
        ReflectionTestUtils.setField(saved, "id", 10L);
        when(webhookEventRepository.save(any())).thenReturn(saved);

        webhookEventInboxService.receive("tx-2", "2026-08-21T00:00:00+09:00",
                "PAYMENT_STATUS_CHANGED", "order-2", "{}");

        verify(webhookEventRepository).save(any());
        verify(applicationEventPublisher).publishEvent(
                org.mockito.ArgumentMatchers.argThat((WebhookEventReceivedEvent e) -> e.getWebhookEventId().equals(10L)));
    }

    @Test
    void receive_whenSaveFailsOnRaceWithConcurrentRetransmission_doesNotPublish() {
        // existsByTransmissionId 확인 이후 동시 재전송으로 경합해 save()가 UNIQUE
        // 제약을 위반하는 상황을 흉내낸다. 이 경우 이벤트를 발행하면 안 된다 —
        // 커밋되지도 않은 저장에 대해 처리를 트리거하는 꼴이 되기 때문이다.
        when(webhookEventRepository.existsByTransmissionId("tx-race")).thenReturn(false);
        when(webhookEventRepository.save(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> webhookEventInboxService.receive("tx-race", "2026-08-21T00:00:00+09:00",
                        "PAYMENT_STATUS_CHANGED", "order-race", "{}"));

        verify(applicationEventPublisher, never()).publishEvent(any());
    }
}
