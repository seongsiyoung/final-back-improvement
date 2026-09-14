package com.example.finalproject.payment.service;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.product.service.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인에 이르지 못하고 종결된 결제가 남긴 것을 정리한다. 선점을 반납하고 주문을 종결한다.
 *
 * <p>자체 트랜잭션을 열지 않는다. 정리는 결제를 종결시키는 전이와 **같은 트랜잭션**이어야 한다.
 * 나뉘면 결제만 종결되고 선점이나 주문이 남는 구간이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TerminatedPaymentCleanupService {

    private final StockReservationService stockReservationService;

    @Transactional(propagation = Propagation.MANDATORY)
    public void cleanUp(Payment payment) {
        // 승인까지 간 결제는 선점을 이미 실재고 차감으로 확정했고 주문도 PAID 다.
        // 반납하면 같은 상품에 남은 다른 결제의 선점을 대신 깎고, 주문 상태도 잘못 뒤집는다.
        if (payment.hasApprovalRecord()) {
            return;
        }

        stockReservationService.releaseFor(payment.getOrder().getId());
        payment.getOrder().cancelUnpaid();
    }
}
