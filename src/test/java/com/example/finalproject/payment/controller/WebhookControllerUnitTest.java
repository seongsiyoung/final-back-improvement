package com.example.finalproject.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.finalproject.payment.service.WebhookEventInboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * WebhookController가 "항상 200을 반환한다"는 핵심 방어를 스프링 컨텍스트 없이
 * 직접 검증한다. IntegrationTestSupport 기반 WebhookControllerTest는 정상 경로만
 * 다루고, DB 제약 위반이나 헤더 누락처럼 강제로 재현하기 번거로운 경합 상황은
 * 여기서 서비스를 목으로 대체해 확인한다.
 */
class WebhookControllerUnitTest {

    private WebhookEventInboxService webhookEventInboxService;
    private WebhookController webhookController;

    @BeforeEach
    void setUp() {
        webhookEventInboxService = mock(WebhookEventInboxService.class);
        webhookController = new WebhookController(webhookEventInboxService, new ObjectMapper());
    }

    private String validBody(String orderId) {
        return """
                {
                  "eventType": "PAYMENT_STATUS_CHANGED",
                  "data": { "paymentKey": "pk-1", "orderId": "%s", "status": "DONE" }
                }
                """.formatted(orderId);
    }

    @Test
    void receiveTossWebhook_whenTransmissionIdHeaderMissing_returns200AndSkipsSave() {
        ResponseEntity<Void> response = webhookController.receiveTossWebhook(
                validBody("order-1"), null, "2026-08-21T00:00:00+09:00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(webhookEventInboxService, never()).receive(any(), any(), any(), any(), any());
    }

    @Test
    void receiveTossWebhook_whenSaveRacesOnUniqueConstraint_stillReturns200() {
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(webhookEventInboxService)
                .receive(anyString(), anyString(), anyString(), anyString(), anyString());

        ResponseEntity<Void> response = webhookController.receiveTossWebhook(
                validBody("order-1"), "tx-race", "2026-08-21T00:00:00+09:00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
