package com.example.finalproject.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.enums.WebhookEventStatus;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class WebhookEventRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private WebhookEventRepository webhookEventRepository;

    @Test
    void save_thenReload_returnsPayloadAsPlainText() {
        // payload 컬럼이 Postgres의 대용량 객체(oid)가 아니라 TEXT로 매핑됐는지
        // 확인한다 — @Lob String이 dialect에 따라 oid로 잡히면 저장 자체는 되지만
        // 원문을 그대로 못 읽어올 수 있다.
        String rawPayload = "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"orderId\":\"order-9\"}}";
        Long savedId = webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId("tx-roundtrip")
                        .transmissionTime("2026-08-21T00:00:00+09:00")
                        .eventType("PAYMENT_STATUS_CHANGED")
                        .orderId("order-9")
                        .payload(rawPayload)
                        .build()).getId();

        WebhookEvent reloaded = webhookEventRepository.findById(savedId).orElseThrow();

        assertThat(reloaded.getPayload()).isEqualTo(rawPayload);
    }

    @Test
    void save_persistsWithReceivedStatus() {
        WebhookEvent event = webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId("tx-1")
                        .transmissionTime("2026-08-21T00:00:00+09:00")
                        .eventType("PAYMENT_STATUS_CHANGED")
                        .orderId("order-1")
                        .payload("{}")
                        .build());

        assertThat(event.getId()).isNotNull();
        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.RECEIVED);
    }

    @Test
    void save_whenTransmissionIdDuplicated_violatesUniqueConstraint() {
        webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId("tx-dup")
                        .transmissionTime("2026-08-21T00:00:00+09:00")
                        .eventType("PAYMENT_STATUS_CHANGED")
                        .orderId("order-1")
                        .payload("{}")
                        .build());

        assertThatThrownBy(() -> {
            webhookEventRepository.save(
                    WebhookEvent.builder()
                            .transmissionId("tx-dup")
                            .transmissionTime("2026-08-21T00:01:00+09:00")
                            .eventType("PAYMENT_STATUS_CHANGED")
                            .orderId("order-2")
                            .payload("{}")
                            .build());
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existsByTransmissionId_afterSave_returnsTrue() {
        webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId("tx-exists")
                        .transmissionTime("2026-08-21T00:00:00+09:00")
                        .eventType("PAYMENT_STATUS_CHANGED")
                        .orderId("order-1")
                        .payload("{}")
                        .build());

        assertThat(webhookEventRepository.existsByTransmissionId("tx-exists")).isTrue();
        assertThat(webhookEventRepository.existsByTransmissionId("tx-none")).isFalse();
    }

    @Test
    void markProcessed_setsStatusAndProcessedAt() {
        WebhookEvent event = webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId("tx-processed")
                        .transmissionTime("2026-08-21T00:00:00+09:00")
                        .eventType("PAYMENT_STATUS_CHANGED")
                        .orderId("order-1")
                        .payload("{}")
                        .build());

        event.markProcessed();

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    void markFailed_setsStatusAndProcessedAt() {
        WebhookEvent event = webhookEventRepository.save(
                WebhookEvent.builder()
                        .transmissionId("tx-failed")
                        .transmissionTime("2026-08-21T00:00:00+09:00")
                        .eventType("PAYMENT_STATUS_CHANGED")
                        .orderId("order-1")
                        .payload("{}")
                        .build());

        event.markFailed();

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(event.getProcessedAt()).isNotNull();
    }
}
