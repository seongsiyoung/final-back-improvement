package com.example.finalproject.payment.listener;

import com.example.finalproject.payment.event.WebhookEventReceivedEvent;
import com.example.finalproject.payment.service.WebhookEventProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 인박스 저장 트랜잭션이 커밋된 뒤에만(AFTER_COMMIT) 별도 스레드(@Async)에서 처리를
 * 시작한다. 컨트롤러 안에서 @Async를 직접 호출하면 커밋 전에 이 리스너가 실행되는
 * 레이스가 생긴다 — docs/04-webhook-inbox/design.md "왜 이렇게 했는가" 참고.
 */
@Component
@RequiredArgsConstructor
public class WebhookEventListener {

    private final WebhookEventProcessor webhookEventProcessor;

    @Async("webhookExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReceived(WebhookEventReceivedEvent event) {
        webhookEventProcessor.process(event.getWebhookEventId());
    }
}
