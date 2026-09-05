package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * PG는 승인했는데 우리 DB엔 반영되지 않은 PENDING 결제를 정리한다.
 * 웹훅(빠른 경로)과 재조회 배치(안전망)가 이 서비스를 공유한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private static final String DONE_STATUS = "DONE";
    private static final String CANCELED_STATUS = "CANCELED";
    private static final String PARTIAL_CANCELED_STATUS = "PARTIAL_CANCELED";
    private static final Set<String> FAILED_STATUSES = Set.of("ABORTED", CANCELED_STATUS, "EXPIRED");

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentConfirmCommandService paymentConfirmCommandService;

    public void reconcile(Payment payment) {
        if (payment.getPaymentStatus() != PaymentStatus.PENDING
                && payment.getPaymentStatus() != PaymentStatus.REVERSAL_PENDING) return;
        TossConfirmResponse pg;
        try {
            pg = tossPaymentsClient.getPaymentByOrderId(payment.getPgOrderId());
        } catch (FeignException.NotFound e) {
            // REVERSAL_PENDING은 Toss가 승인 성공을 돌려준 뒤에만 붙는 상태다.
            // 그런데 조회에 기록이 없다면 두 사실이 모순이므로 승인 여부를 단정할 수 없다.
            // FAILED로 적으면 돈이 나간 결제를 "돈이 안 나갔음"으로 확정하게 되고,
            // 그 상태를 집어가는 스케줄러 조건이 없어 영구히 사라진다.
            if (payment.getPaymentStatus() == PaymentStatus.REVERSAL_PENDING) {
                log.error("보상 취소 대상인데 PG에 결제 기록이 없어 확인 필요로 남김. paymentId={}, pgOrderId={}",
                        payment.getId(), payment.getPgOrderId());
                paymentConfirmCommandService.markConfirmReconciliationRequired(payment.getId());
                return;
            }

            // PENDING은 승인 응답을 받은 적이 없다. 기록이 없다면 승인된 적 없음이 확정된다.
            log.info("PG에 결제 기록이 없어 실패 처리함. paymentId={}, pgOrderId={}",
                    payment.getId(), payment.getPgOrderId());
            paymentConfirmCommandService.failPending(payment.getId());
            return;
        }

        if (payment.getPaymentStatus() == PaymentStatus.REVERSAL_PENDING) {
            if (CANCELED_STATUS.equals(pg.getStatus())) {
                paymentConfirmCommandService.failReversalPending(payment.getId());
            } else {
                log.info("PG 취소 상태가 확정되지 않아 유지함. paymentId={}, status={}", payment.getId(), pg.getStatus());
            }
            return;
        }

        try {
            String status = pg.getStatus();
            if (DONE_STATUS.equals(status)) {
                paymentConfirmCommandService.completeConfirm(payment.getId(), pg.getPaymentKey(), pg);
            } else if (PARTIAL_CANCELED_STATUS.equals(status)) {
                log.error("PG 결제가 부분 취소돼 확인 필요로 남김. paymentId={}, status={}", payment.getId(), status);
                paymentConfirmCommandService.markConfirmReconciliationRequired(payment.getId());
            } else if (status != null && FAILED_STATUSES.contains(status)) {
                log.info("PG가 실패를 확정해 실패 처리함. paymentId={}, status={}",
                        payment.getId(), status);
                paymentConfirmCommandService.failPending(payment.getId());
            } else {
                log.info("PG 상태가 아직 종결되지 않아 유지함. paymentId={}, status={}",
                        payment.getId(), status);
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.ALREADY_PROCESSED_PAYMENT) {
                log.info("이미 다른 경로(웹훅/배치/원 요청)에서 처리된 결제라 무시함. paymentId={}",
                        payment.getId());
                return;
            }
            throw e;
        }
    }
}
