package com.example.finalproject.product.scheduler;

import com.example.finalproject.product.service.StockReservationDriftChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 선점 장부가 어긋났는지 주기적으로 확인한다. 고치지는 않는다. */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationDriftScheduler {

    private final StockReservationDriftChecker stockReservationDriftChecker;

    @Scheduled(fixedDelayString = "${reservation.drift-scan-interval-ms:600000}")
    public void reportStockReservationDrift() {
        try {
            stockReservationDriftChecker.reportDrift();
        } catch (Exception e) {
            log.error("선점 드리프트 점검 실패", e);
        }
    }
}
