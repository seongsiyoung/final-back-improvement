package com.example.finalproject.order.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderProduct;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.event.StoreOrderRejectedEvent;
import com.example.finalproject.order.repository.OrderProductRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
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

    @Transactional
    public void handleRefundCompletion(Long storeOrderId, String reason) {

        StoreOrder storeOrder = storeOrderRepository.findById(storeOrderId)
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

        log.error("[REFUND_EVENT_STATE_MISMATCH] storeOrderId={}, status={}",
                storeOrderId, storeOrder.getStatus());
        throw new BusinessException(ErrorCode.INVALID_STORE_ORDER_REFUND_STATUS);
    }

    @Transactional
    public void requestCancel(Long storeOrderId, String reason) {

        StoreOrder storeOrder = storeOrderRepository.findById(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        storeOrder.requestCancel();
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
