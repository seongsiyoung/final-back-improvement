package com.example.finalproject.payment.controller;

import com.example.finalproject.payment.dto.webhook.TossWebhookPayload;
import com.example.finalproject.payment.service.WebhookEventInboxService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Toss 웹훅 수신. 인박스 패턴 — 본문을 저장만 하고 즉시 200을 반환한다.
 * 실제 처리(재조회·반영)는 커밋 이후 WebhookEventListener(AFTER_COMMIT + @Async)가 한다.
 * 결제 웹훅에는 서명 헤더가 없어(부록 B) 본문을 신뢰하지 않고, 여기서는 "재조회하라"는
 * 트리거로만 쓴다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/payments/webhooks")
public class WebhookController {

    private final WebhookEventInboxService webhookEventInboxService;
    private final ObjectMapper objectMapper;

    @PostMapping("/toss")
    public ResponseEntity<Void> receiveTossWebhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "tosspayments-webhook-transmission-id", required = false)
            String transmissionId,
            @RequestHeader(value = "tosspayments-webhook-transmission-time", required = false)
            String transmissionTime) {

        // Toss 공식 문서상 이 헤더는 항상 온다고 되어 있지만, 못 믿을 이유(서명도 없음)가
        // 이미 있는 채널이라 required=true로 걸어 400을 돌려주는 대신 방어적으로 처리한다.
        // 이 헤더 없이는 중복 판별이 불가능하므로 저장은 건너뛰고 200만 반환한다 — Toss가
        // "실패"로 오인해 불필요하게 재전송하는 걸 막는다.
        if (transmissionId == null || transmissionId.isBlank()) {
            log.warn("tosspayments-webhook-transmission-id 헤더 없이 웹훅이 들어와 저장을 건너뜀");
            return ResponseEntity.ok().build();
        }

        TossWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, TossWebhookPayload.class);
        } catch (JsonProcessingException e) {
            log.warn("웹훅 본문 파싱 실패. transmissionId={}", transmissionId, e);
            return ResponseEntity.ok().build();
        }

        String orderId = payload.getData() != null ? payload.getData().getOrderId() : null;

        try {
            webhookEventInboxService.receive(transmissionId, transmissionTime, payload.getEventType(),
                    orderId, rawBody);
        } catch (DataIntegrityViolationException e) {
            // 존재 여부 확인과 저장 사이의 짧은 경합 — 동시 재전송으로만 발생.
            // 이 catch가 실제로 걸리려면 WebhookEvent가 GenerationType.IDENTITY를 써서
            // save() 호출 시점에 즉시 INSERT가 실행돼야 한다. 이 전략을 SEQUENCE 등으로
            // 바꾸면 제약 위반이 커밋 시점까지 지연돼 TransactionSystemException으로
            // 나타나고, 이 catch에 안 걸려 500이 될 수 있다.
            log.info("동시 재전송으로 인한 중복 저장 시도 무시함. transmissionId={}", transmissionId);
        }

        return ResponseEntity.ok().build();
    }
}
