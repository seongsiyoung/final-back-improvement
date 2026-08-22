package com.example.finalproject.payment.service;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.WebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 웹훅 이벤트 처리 진입점. 조회 실패를 포함한 모든 예외를 여기서 잡아 WebhookEvent.status를
 * PROCESSED/FAILED로 확정한다 — @Async의 기본 예외 처리는 로깅만 하고 삼키므로, 여기서
 * 명시적으로 잡지 않으면 상태가 RECEIVED에 영원히 머문다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookEventProcessor {

    private static final String PAYMENT_STATUS_CHANGED = "PAYMENT_STATUS_CHANGED";

    private final WebhookEventRepository webhookEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationService paymentReconciliationService;
    private final WebhookEventStatusService webhookEventStatusService;

    public void process(Long webhookEventId) {
        WebhookEvent event = webhookEventRepository.findById(webhookEventId).orElse(null);
        if (event == null) {
            log.error("처리할 웹훅 이벤트를 찾을 수 없음. webhookEventId={}", webhookEventId);
            return;
        }

        try {
            if (!PAYMENT_STATUS_CHANGED.equals(event.getEventType())) {
                webhookEventStatusService.markProcessed(webhookEventId);
                return;
            }

            Payment payment = paymentRepository.findByPgOrderId(event.getOrderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "웹훅의 orderId에 해당하는 결제를 찾을 수 없음: " + event.getOrderId()));

            paymentReconciliationService.reconcile(payment);
            webhookEventStatusService.markProcessed(webhookEventId);
        } catch (Exception e) {
            log.error("웹훅 이벤트 처리 실패. webhookEventId={}", webhookEventId, e);
            webhookEventStatusService.markFailed(webhookEventId);
        }
    }
}
