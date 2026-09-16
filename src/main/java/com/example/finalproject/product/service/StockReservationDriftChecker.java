package com.example.finalproject.product.service;

import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.product.repository.ProductRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 선점량과 미종결 결제의 주문 수량을 대조한다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationDriftChecker {

    private static final List<PaymentStatus> RESERVATION_HOLDING_STATUSES = List.of(
            PaymentStatus.READY,
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.RECONCILIATION_REQUIRED);

    private final OrderLineRepository orderLineRepository;
    private final ProductRepository productRepository;

    /** 동일 스냅샷에서 기대 선점량과 실제 선점량을 비교한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Long> reportDrift() {
        Map<Long, Long> expected = toMap(
                orderLineRepository.sumReservedQuantityByProduct(RESERVATION_HOLDING_STATUSES));
        Map<Long, Long> actual = toMap(productRepository.findReservedQuantities());

        Set<Long> productIds = new TreeSet<>(expected.keySet());
        productIds.addAll(actual.keySet());

        List<Long> drifted = new ArrayList<>();
        for (Long productId : productIds) {
            long expectedQuantity = expected.getOrDefault(productId, 0L);
            long actualQuantity = actual.getOrDefault(productId, 0L);

            if (expectedQuantity != actualQuantity) {
                drifted.add(productId);
                log.error("[STOCK_RESERVATION_DRIFT_DETECTED] productId={}, expected={}, actual={}, diff={}",
                        productId, expectedQuantity, actualQuantity, actualQuantity - expectedQuantity);
            }
        }

        if (!drifted.isEmpty()) {
            log.error("[STOCK_RESERVATION_DRIFT_SUMMARY] 불일치 상품 {}건. 자동 교정하지 않는다.", drifted.size());
        }
        return drifted;
    }

    private Map<Long, Long> toMap(List<Object[]> rows) {
        Map<Long, Long> result = new HashMap<>();
        for (Object[] row : rows) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return result;
    }
}
