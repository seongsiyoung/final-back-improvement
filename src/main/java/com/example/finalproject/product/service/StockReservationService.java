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

/** 주문이 보유한 상품 선점을 반납한다. */
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

            // 드리프트가 있어도 결제 종결을 막지 않도록 실제 선점량까지만 반납한다.
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
