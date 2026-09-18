package com.example.finalproject.payment.service;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.product.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 미승인 종결 결제의 선점을 반납하고 주문을 취소한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminatedPaymentCleanupService {

    private final StockReservationService stockReservationService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanUp(Payment payment) {
        if (payment.hasApprovalRecord()) {
            return;
        }

        stockReservationService.releaseFor(payment.getOrder().getId());
        payment.getOrder().cancelUnpaid();
    }
}
