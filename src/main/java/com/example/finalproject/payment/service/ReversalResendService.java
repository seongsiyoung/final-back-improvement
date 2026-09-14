package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossIdempotencyKeys;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.config.TossCircuitBreakerFallback;
import com.example.finalproject.payment.dto.request.TossCancelRequest;
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

/**
 * 나가지 못한 보상 취소를 다시 보낸다.
 *
 * <p>REVERSAL_PENDING 은 PG 승인이 성공한 뒤 로컬 반영이 실패해 취소를 걸었는데 그 결과도
 * 모르는 상태다. 조회 결과가 DONE 이면 취소가 PG 에 닿지 않았다는 뜻이다 — 회로가 열려
 * 요청이 아예 나가지 않았거나 전송 중 끊겼다. 다시 보내지 않으면 고객 돈이 묶인 채 남는다.
 *
 * <p>트랜잭션을 열지 않는다. PG 호출은 트랜잭션 밖이어야 하고, 상태 전이는 호출되는
 * 커맨드 서비스가 각자 자기 트랜잭션에서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReversalResendService {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentConfirmCommandService paymentConfirmCommandService;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    /**
     * 취소를 다시 보내고 결과에 따라 종결한다.
     *
     * <p>호출자가 PG 상태를 DONE 으로 확인한 뒤에만 부른다. 이미 취소된 건은 조회가 CANCELED 를
     * 돌려주므로 여기 오지 않는다. 멱등키를 원 요청과 같게 쓰는 것은 원 요청의 취소가 아직
     * 인플라이트일 때를 위한 이중 안전장치다.
     */
    public void resend(Long paymentId, String paymentKey, int amount) {
        try {
            String idempotencyKey = TossIdempotencyKeys.forCompensatingCancel(paymentId);
            circuitBreakerFactory.create("toss-payment")
                    .run(() -> {
                        tossPaymentsClient.cancel(paymentKey,
                                new TossCancelRequest("재고 부족으로 결제 취소", amount), idempotencyKey);
                        return null;
                    }, TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException e) {
            PgCallOutcome outcome = PgFailureClassifier.classify(e);
            log.error("[PG_REVERSAL_RESEND_ERROR] paymentId={}, outcome={}", paymentId, outcome, e);

            // 원 요청 경로와 같은 분류를 쓴다. PG 가 명확히 거절하면 자동으로 할 수 있는 일이
            // 없으므로 사람에게 넘기고, 그 밖이면 상태를 두고 다음 주기에 다시 시도한다.
            if (outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                paymentConfirmCommandService.markConfirmReconciliationRequired(paymentId);
            }
            return;
        }

        log.info("[PG_REVERSAL_RESENT] paymentId={}", paymentId);
        paymentConfirmCommandService.failReversalPending(paymentId);
    }
}
