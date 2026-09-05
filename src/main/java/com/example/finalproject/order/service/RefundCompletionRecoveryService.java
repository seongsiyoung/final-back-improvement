package com.example.finalproject.order.service;

import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * applyRefund 커밋 후 AFTER_COMMIT 리스너가 실패해 주문 후속 처리가 남은 건을 되살린다.
 *
 * <p>@Transactional 이 없다. handleRefundCompletion 이 자체 트랜잭션과 비관적 락을 가진다.
 * 여기에 트랜잭션을 붙이면 락 구간이 스캔 루프 전체로 늘어난다.
 *
 * <p>인자로 받는 StoreOrder 는 스캔이 트랜잭션 밖에서 읽은 것이라 detached 다.
 * 식별자만 읽는다. 연관을 타면 LazyInitializationException 이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefundCompletionRecoveryService {

    private final StoreOrderStatusService storeOrderStatusService;
    private final PaymentRefundRepository paymentRefundRepository;

    public void recover(StoreOrder storeOrder) {
        Long storeOrderId = storeOrder.getId();

        // 5단계 이후 한 주문에 환불 이력이 여러 건일 수 있다. 마지막 건을 본다.
        PaymentRefund latest = paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(storeOrderId)
                .stream()
                .findFirst()
                .orElse(null);

        // 스캔은 Payment 상태로 대상을 고르는데 Payment 는 주문 단위이고 StoreOrder 는 매장 단위다.
        // 같은 주문의 다른 매장이 환불돼 결제가 REFUNDED 가 되면, 환불된 적 없는 매장 주문이
        // 대상에 섞여 들어온다. 그 건을 취소로 확정하면 돈은 그대로인데 재고만 늘어난다.
        if (latest == null || latest.getRefundStatus() != RefundStatus.APPROVED) {
            log.error("[REFUND_COMPLETION_RECOVERY_SKIPPED] 승인된 환불 이력이 없다. storeOrderId={}, latest={}",
                    storeOrderId, latest == null ? null : latest.getRefundStatus());
            return;
        }

        log.info("[REFUND_COMPLETION_RECOVERY] storeOrderId={}", storeOrderId);
        storeOrderStatusService.handleRefundCompletion(storeOrderId, latest.getRefundReason());
    }
}
