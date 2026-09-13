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

/**
 * `Product.reserved` 가 미종결 결제의 주문 수량 합과 맞는지 대조한다.
 *
 * <p>어긋난 값을 고치지 않는다. 어느 쪽이 맞는지 모르는 채로 고치면 더 나빠진다 —
 * 선점이 모자란 것인지 결제가 잘못 남은 것인지는 사람이 봐야 안다. 여기서는 사실만 남긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockReservationDriftChecker {

    /** 선점이 살아 있어야 하는 결제 상태. 종결 전이가 일어나면 반납되므로 여기서 빠진다. */
    private static final List<PaymentStatus> RESERVATION_HOLDING_STATUSES = List.of(
            PaymentStatus.READY,
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.RECONCILIATION_REQUIRED);

    private final OrderLineRepository orderLineRepository;
    private final ProductRepository productRepository;

    /**
     * 두 쿼리가 같은 스냅샷을 봐야 한다. READ COMMITTED 면 문장마다 스냅샷이 달라, 그 사이에
     * 커밋된 prepare() 나 종결이 한쪽에만 보여 거짓 불일치가 나온다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Long> reportDrift() {
        Map<Long, Long> expected = toMap(
                orderLineRepository.sumReservedQuantityByProduct(RESERVATION_HOLDING_STATUSES));
        Map<Long, Long> actual = toMap(productRepository.findReservedQuantities());

        // 한쪽에만 있는 상품도 드리프트다. 합집합을 돈다.
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
