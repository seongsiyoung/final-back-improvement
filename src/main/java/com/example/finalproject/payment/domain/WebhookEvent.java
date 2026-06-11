package com.example.finalproject.payment.domain;

import com.example.finalproject.global.domain.BaseTimeEntity;
import com.example.finalproject.payment.enums.WebhookEventStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Toss 웹훅 인박스. 저장 후 즉시 200을 반환하고, 실제 처리(재조회·반영)는
 * 커밋 이후 별도로 한다.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transmission_id", nullable = false, unique = true, length = 100)
    private String transmissionId;

    @Column(name = "transmission_time", length = 50)
    private String transmissionTime;

    @Column(name = "event_type", length = 50)
    private String eventType;

    // Payment.pgOrderId 생성 형식("PG-ORD-" + 타임스탬프 + "-" + UUID 8자리)이 30자
    // 안팎이라 50자면 충분하다.
    @Column(name = "order_id", length = 50)
    private String orderId;

    // Hibernate 6에서 @Lob String은 dialect에 따라 Postgres의 대용량 객체(oid)로
    // 매핑될 수 있다 — 그러면 psql 등으로 원문을 바로 못 읽고, 삭제 시 자동 정리도
    // 안 된다. 웹훅 본문은 JSON 텍스트라 진짜 LOB이 필요 없어 TEXT로 명시한다.
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WebhookEventStatus status;

    private LocalDateTime processedAt;

    @Builder
    public WebhookEvent(String transmissionId, String transmissionTime, String eventType,
                         String orderId, String payload) {
        this.transmissionId = transmissionId;
        this.transmissionTime = transmissionTime;
        this.eventType = eventType;
        this.orderId = orderId;
        this.payload = payload;
        this.status = WebhookEventStatus.RECEIVED;
    }

    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = WebhookEventStatus.FAILED;
        this.processedAt = LocalDateTime.now();
    }
}
