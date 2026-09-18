package com.example.finalproject.payment.service;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

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
    private static final String PARTIAL_CANCELED_STATUS = "PARTIAL_CANCELED";
    private static final Set<String> FAILED_STATUSES = Set.of("ABORTED", CANCELED_STATUS, "EXPIRED");

    private final TossPaymentsClient tossPaymentsClient;
    private final SubscriptionChargeCommandService subscriptionChargeCommandService;
    private final ReconciliationAttemptCommandService reconciliationAttemptCommandService;
    private final SubscriptionReversalResendService subscriptionReversalResendService;

    public void reconcile(SubscriptionPayment payment) {
        PaymentStatus status = payment.getPaymentStatus();
        if (status != PaymentStatus.PENDING && status != PaymentStatus.REVERSAL_PENDING) {
            return;
        }

        Long paymentId = payment.getId();

        boolean lookupSucceeded = false;
        boolean unresolved = false;
        TossConfirmResponse pg;
        try {
            try {
                pg = tossPaymentsClient.getPaymentByOrderId(payment.getPgOrderId());
                lookupSucceeded = true;
            } catch (FeignException.NotFound e) {
                lookupSucceeded = true;
                // 승인 성공 후 상태와 PG 미존재 응답이 모순이므로 자동 종결하지 않는다.
                if (status == PaymentStatus.REVERSAL_PENDING) {
                    log.error("[SUB_RECONCILE_NO_PG_RECORD] 보상 취소 대상인데 PG 기록이 없어 확인 필요로 남김. "
                            + "subscriptionPaymentId={}, pgOrderId={}", paymentId, payment.getPgOrderId());
                    subscriptionChargeCommandService.markReconciliationRequired(paymentId);
                    return;
                }

                log.info("[SUB_RECONCILE_NOT_FOUND] PG 기록이 없어 실패 처리함. subscriptionPaymentId={}, pgOrderId={}",
                        paymentId, payment.getPgOrderId());
                subscriptionChargeCommandService.failCharge(paymentId);
                return;
            }

            if (status == PaymentStatus.REVERSAL_PENDING) {
                if (CANCELED_STATUS.equals(pg.getStatus())) {
                    subscriptionChargeCommandService.failReversalPending(paymentId);
                } else if (DONE_STATUS.equals(pg.getStatus())) {
                    // PG에 반영되지 않은 보상 취소를 재전송한다.
                    unresolved = true;
                    log.info("[SUB_REVERSAL_NOT_REACHED_PG] 보상 취소를 재전송함. subscriptionPaymentId={}",
                            paymentId);
                    subscriptionReversalResendService.resend(
                            paymentId, pg.getPaymentKey(), payment.getAmount());
                } else {
                    unresolved = true;
                    log.info("[SUB_RECONCILE_REVERSAL_UNCONFIRMED] PG 취소 상태가 확정되지 않아 유지함. "
                            + "subscriptionPaymentId={}, status={}", paymentId, pg.getStatus());
                }
                return;
            }

            String pgStatus = pg.getStatus();
            if (PARTIAL_CANCELED_STATUS.equals(pgStatus)) {
                log.error("[SUB_RECONCILE_PARTIAL_CANCELED] PG 결제가 부분 취소돼 확인 필요로 남김. "
                        + "subscriptionPaymentId={}, status={}", paymentId, pgStatus);
                subscriptionChargeCommandService.markReconciliationRequired(paymentId);
                return;
            }

            if (pgStatus != null && FAILED_STATUSES.contains(pgStatus)) {
                log.info("[SUB_RECONCILE_FAILED] PG가 실패를 확정해 실패 처리함. "
                        + "subscriptionPaymentId={}, status={}", paymentId, pgStatus);
                subscriptionChargeCommandService.failCharge(paymentId);
                return;
            }

            if (!DONE_STATUS.equals(pgStatus)) {
                unresolved = true;
                log.info("[SUB_RECONCILE_UNRESOLVED] PG 상태가 아직 종결되지 않아 유지함. "
                        + "subscriptionPaymentId={}, status={}", paymentId, pgStatus);
                return;
            }

            TossConfirmResponse.Card card = pg.getCard();
            subscriptionChargeCommandService.completeReconciledCharge(
                    paymentId,
                    pg.getPaymentKey(),
                    card == null ? null : card.getCompany(),
                    card == null ? null : card.getNumber());
        } finally {
            reconciliationAttemptCommandService.recordSubscriptionPaymentAttempt(
                    paymentId, lookupSucceeded && unresolved);
        }
    }
}
