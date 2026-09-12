package com.example.finalproject.product.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제가 종결될 때 그 주문이 잡아둔 선점을 되돌린다.
 *
 * <p>자체 트랜잭션을 열지 않는다. 반납은 결제 상태를 종결시키는 전이와 **같은 트랜잭션**이어야
 * 한다. 나뉘면 상태만 종결되고 선점이 남거나, 선점만 풀리고 상태가 되돌아가는 구간이 생긴다.
 *
 * <p>잠그는 순서는 prepare() 의 선점과 같은 productId 오름차순이다. 순서가 다르면 선점과 반납이
 * 서로의 락을 마주 기다린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationService {

    private final OrderLineRepository orderLineRepository;
    private final ProductRepository productRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseFor(Long orderId) {
        List<OrderLine> lines = orderLineRepository.findAllByOrderId(orderId).stream()
                .sorted(Comparator.comparing(OrderLine::getProductId))
                .toList();

        for (OrderLine line : lines) {
            Product product = productRepository.findByIdForUpdate(line.getProductId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

            // 선점보다 많이 반납하려 하면 어딘가에서 장부가 어긋난 것이다. 그래도 예외를 던지지
            // 않는다 — 여기서 막으면 결제 종결 자체가 커밋되지 않아 결제가 미확정에 갇히고
            // 재조정이 같은 실패를 반복한다. 있는 만큼만 되돌리고 흔적을 남긴다.
            int releasable = Math.min(product.getReserved(), line.getQuantity());
            if (releasable < line.getQuantity()) {
                log.error("[STOCK_RESERVATION_DRIFT] 선점이 모자라 일부만 반납함. orderId={}, productId={}, "
                                + "quantity={}, reserved={}",
                        orderId, product.getId(), line.getQuantity(), product.getReserved());
            }
            if (releasable > 0) {
                product.releaseReservation(releasable);
            }
        }

        log.debug("[STOCK_RESERVATION_RELEASED] orderId={}, lineCount={}", orderId, lines.size());
    }
}
