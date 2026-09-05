package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결과가 미확정인 채로 멈춘 구독 결제를 정리한다. 일반 결제의
 * PaymentReconciliationService 와 같은 기준으로 판단한다.
 *
 * <p>@Transactional 이 없다. PG 조회를 트랜잭션 밖에서 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionReconciliationService {

    private static final String DONE_STATUS = "DONE";
    private static final String CANCELED_STATUS = "CANCELED";

    private final TossPaymentsClient tossPaymentsClient;
    private final SubscriptionChargeCommandService subscriptionChargeCommandService;

    public void reconcile(SubscriptionPayment payment) {
        PaymentStatus status = payment.getPaymentStatus();
        if (status != PaymentStatus.PENDING && status != PaymentStatus.REVERSAL_PENDING) {
            return;
        }

        Long paymentId = payment.getId();

        TossConfirmResponse pg;
        try {
            // 조회 실패는 잡지 않는다. 상태를 바꾸지 않아야 다음 주기에 다시 시도된다.
            pg = tossPaymentsClient.getPaymentByOrderId(payment.getPgOrderId());
        } catch (FeignException.NotFound e) {
            // REVERSAL_PENDING 은 Toss 가 승인 성공을 돌려준 뒤에만 붙는 상태다.
            // 기록이 없다는 응답과 모순이므로 승인 여부를 단정할 수 없다.
            if (status == PaymentStatus.REVERSAL_PENDING) {
                log.error("[SUB_RECONCILE_NO_PG_RECORD] 보상 취소 대상인데 PG 기록이 없어 확인 필요로 남김. "
                        + "subscriptionPaymentId={}, pgOrderId={}", paymentId, payment.getPgOrderId());
                subscriptionChargeCommandService.markReconciliationRequired(paymentId);
                return;
            }

            // PENDING 은 승인 응답을 받은 적이 없다. 기록이 없다면 승인된 적 없음이 확정된다.
            log.info("[SUB_RECONCILE_NOT_FOUND] PG 기록이 없어 실패 처리함. subscriptionPaymentId={}, pgOrderId={}",
                    paymentId, payment.getPgOrderId());
            subscriptionChargeCommandService.failCharge(paymentId);
            return;
        }

        if (status == PaymentStatus.REVERSAL_PENDING) {
            if (CANCELED_STATUS.equals(pg.getStatus())) {
                subscriptionChargeCommandService.failReversalPending(paymentId);
            } else {
                log.info("[SUB_RECONCILE_REVERSAL_UNCONFIRMED] PG 취소 상태가 확정되지 않아 유지함. "
                        + "subscriptionPaymentId={}, status={}", paymentId, pg.getStatus());
            }
            return;
        }

        if (!DONE_STATUS.equals(pg.getStatus())) {
            log.info("[SUB_RECONCILE_NOT_DONE] PG 상태가 DONE 이 아니어서 실패 처리함. "
                    + "subscriptionPaymentId={}, status={}", paymentId, pg.getStatus());
            subscriptionChargeCommandService.failCharge(paymentId);
            return;
        }

        // 승인 확정과 구독 후처리를 한 트랜잭션에 맡긴다. 정상 경로에서는
        // SubscriptionRecurringProcessor 가 하던 후처리이며, 빠뜨리면 결제는 성공인데
        // 구독은 PAYMENT_FAILED 로 남아 매일 재시도 대상에 오른다.
        TossConfirmResponse.Card card = pg.getCard();
        subscriptionChargeCommandService.completeReconciledCharge(
                paymentId,
                pg.getPaymentKey(),
                card == null ? null : card.getCompany(),
                card == null ? null : card.getNumber());
    }
}
