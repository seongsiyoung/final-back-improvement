package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PG는 승인했는데 우리 DB엔 반영되지 않은 PENDING 결제를 정리한다.
 * 웹훅(빠른 경로)과 재조회 배치(안전망)가 이 서비스를 공유한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationService {

    private static final String DONE_STATUS = "DONE";

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentConfirmCommandService paymentConfirmCommandService;

    public void reconcile(Payment payment) {
        TossConfirmResponse pg;
        try {
            pg = tossPaymentsClient.getPaymentByOrderId(payment.getPgOrderId());
        } catch (FeignException.NotFound e) {
            log.info("PG에 결제 기록이 없어 실패 처리함. paymentId={}, pgOrderId={}",
                    payment.getId(), payment.getPgOrderId());
            paymentConfirmCommandService.failPending(payment.getId());
            return;
        }

        try {
            if (DONE_STATUS.equals(pg.getStatus())) {
                paymentConfirmCommandService.completeConfirm(payment.getId(), pg.getPaymentKey(), pg);
            } else {
                log.info("PG 상태가 DONE이 아니어서 실패 처리함. paymentId={}, status={}",
                        payment.getId(), pg.getStatus());
                paymentConfirmCommandService.failPending(payment.getId());
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
