package com.example.finalproject.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.repository.WebhookEventRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

class WebhookControllerTest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private WebhookEventRepository webhookEventRepository;

    private HttpEntity<String> webhookRequest(String transmissionId, String orderId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("tosspayments-webhook-transmission-id", transmissionId);
        headers.set("tosspayments-webhook-transmission-time", "2026-08-21T00:00:00+09:00");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {
                  "eventType": "PAYMENT_STATUS_CHANGED",
                  "data": { "paymentKey": "pk-1", "orderId": "%s", "status": "DONE" }
                }
                """.formatted(orderId);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void receiveWebhook_savesEventAndReturns200() {
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/payments/webhooks/toss", HttpMethod.POST,
                webhookRequest("tx-controller-1", "order-controller-1"), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<WebhookEvent> events = webhookEventRepository.findAll();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.getTransmissionId()).isEqualTo("tx-controller-1");
            assertThat(event.getOrderId()).isEqualTo("order-controller-1");
            assertThat(event.getEventType()).isEqualTo("PAYMENT_STATUS_CHANGED");
            // status 는 단언하지 않는다. WebhookEventListener 가 AFTER_COMMIT + @Async 로
            // 곧 종결 상태로 바꾸므로 RECEIVED 는 과도 상태다. 이 테스트의 계약은
            // "수신한 웹훅을 인박스에 저장하고 200 을 반환한다"이며,
            // 이후 처리 결과는 WebhookEventProcessingIntegrationTest 가 검증한다.
        });
    }

    @Test
    void receiveWebhook_whenRetransmittedWithSameTransmissionId_stillReturns200_andDoesNotDuplicate() {
        restTemplate.exchange("/api/payments/webhooks/toss", HttpMethod.POST,
                webhookRequest("tx-controller-dup", "order-controller-dup"), Void.class);

        ResponseEntity<Void> secondResponse = restTemplate.exchange(
                "/api/payments/webhooks/toss", HttpMethod.POST,
                webhookRequest("tx-controller-dup", "order-controller-dup"), Void.class);

        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        long count = webhookEventRepository.findAll().stream()
                .filter(e -> "tx-controller-dup".equals(e.getTransmissionId()))
                .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void receiveWebhook_whenBodyIsMalformed_stillReturns200() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("tosspayments-webhook-transmission-id", "tx-malformed");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/payments/webhooks/toss", HttpMethod.POST,
                new HttpEntity<>("이건 JSON이 아니다", headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
