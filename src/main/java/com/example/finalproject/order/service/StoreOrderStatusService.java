package com.example.finalproject.order.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderProduct;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.event.StoreOrderRejectedEvent;
import com.example.finalproject.order.repository.OrderProductRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.service.RefundTarget;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreOrderStatusService {

    private final OrderProductRepository orderProductRepository;
    private final StoreOrderRepository storeOrderRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 환불 완료 후속 처리. AFTER_COMMIT 리스너와 복구 스케줄러가 같은 메서드를 부른다.
     *
     * <p>비관적 락으로 같은 주문의 동시 후속 처리를 직렬화한다. 락이 없으면 두 스레드가
     * 같은 CANCEL_REQUESTED 를 읽어 상태 가드를 나란히 통과하고 재고를 두 번 복구한다.
     *
     * <p>같은 후속 처리가 이미 끝난 상태에서만 no-op 이다. 완료된 건을 전부 넘기면
     * 잘못된 상태까지 삼킨다.
     */
    @Transactional
    public void handleRefundCompletion(Long storeOrderId, String reason) {

        StoreOrder storeOrder = storeOrderRepository.findByIdWithLock(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        if (storeOrder.isCancelRequested()) {
            completeCancel(storeOrder, reason);
            return;
        }

        if (storeOrder.isRejectRequested()) {
            completeReject(storeOrder, reason);
            return;
        }

        if (storeOrder.isRefundRequested()) {
            completeRefund(storeOrder, reason);
            return;
        }

        if (storeOrder.isRefunded()) {
            log.debug("[REFUND_COMPLETION_ALREADY_DONE] storeOrderId={}, status={}",
                    storeOrderId, storeOrder.getStatus());
            return;
        }

        log.error("[REFUND_EVENT_STATE_MISMATCH] storeOrderId={}, status={}",
                storeOrderId, storeOrder.getStatus());
        throw new BusinessException(ErrorCode.INVALID_STORE_ORDER_REFUND_STATUS);
    }

    @Transactional
    public RefundTarget requestCancel(Long storeOrderId, Long userId, String reason) {

        StoreOrder storeOrder = storeOrderRepository.findById(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        if (!storeOrder.getOrder().getUser().getId().equals(userId)) {
            log.warn("[STORE_ORDER_CANCEL_FORBIDDEN] userId={}, storeOrderId={}", userId, storeOrderId);
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        storeOrder.requestCancel();

        return new RefundTarget(
                storeOrder.getOrder().getId(),
                storeOrderId,
                storeOrder.getFinalPrice(),
                reason);
    }

    private void completeCancel(StoreOrder storeOrder, String reason) {

        Order order = storeOrder.getOrder();

        storeOrder.cancel(reason);

        List<OrderProduct> orderProducts = orderProductRepository.findAllByStoreOrderId(storeOrder.getId());
        for (OrderProduct op : orderProducts) {
            op.getProduct().increaseStock(op.getQuantity());
        }

        log.debug("[STORE_ORDER_STOCK_RESTORED] storeOrderId={}, restoredCount={}",
                storeOrder.getId(), orderProducts.size());

        order.recalculateStatus();
    }

    private void completeReject(StoreOrder storeOrder, String reason) {
        storeOrder.completeReject(reason);

        List<OrderProduct> orderProducts = orderProductRepository.findAllByStoreOrderId(storeOrder.getId());
        for (OrderProduct op : orderProducts) {
            op.getProduct().increaseStock(op.getQuantity());
        }

        log.debug("[STORE_ORDER_REJECT_STOCK_RESTORED] storeOrderId={}, restoredCount={}",
                storeOrder.getId(), orderProducts.size());

        storeOrder.getOrder().recalculateStatus();

        eventPublisher.publishEvent(
                new StoreOrderRejectedEvent(
                        storeOrder.getOrder().getUser().getId(),
                        storeOrder.getStore().getStoreName()));
    }

    private void completeRefund(StoreOrder storeOrder, String reason) {
        storeOrder.completeRefund(reason);
        storeOrder.getOrder().recalculateStatus();
    }
}
