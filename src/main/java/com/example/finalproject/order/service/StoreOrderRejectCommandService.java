package com.example.finalproject.order.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderStatus;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.service.RefundTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreOrderRejectCommandService {

    private final StoreOrderRepository storeOrderRepository;
    private final StoreOrderTtlService storeOrderTtlService;

    @Transactional
    public RefundTarget requestReject(Long storeOrderId, String reason) {
        StoreOrder storeOrder = storeOrderRepository.findById(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));
        return applyReject(storeOrder, reason);
    }

    @Transactional
    public RefundTarget requestRejectByOwner(Long storeOrderId, Long storeId, String reason) {
        StoreOrder storeOrder = storeOrderRepository.findByIdWithOrderAndUser(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        if (!storeOrder.getStore().getId().equals(storeId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (storeOrder.getOrder().getStatus() != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID);
        }
        return applyReject(storeOrder, reason);
    }

    @Transactional
    public RefundTarget requestRejectIfPending(Long storeOrderId, String reason) {
        StoreOrder storeOrder = storeOrderRepository.findByIdWithOrderAndUser(storeOrderId)
                .orElse(null);
        if (storeOrder == null || storeOrder.getStatus() != StoreOrderStatus.PENDING) {
            return null;
        }
        return applyReject(storeOrder, reason);
    }

    @Transactional(readOnly = true)
    public Long findOwnerId(Long storeOrderId) {
        return storeOrderRepository.findByIdWithStoreAndOwner(storeOrderId)
                .map(storeOrder -> storeOrder.getStore().getOwner().getId())
                .orElse(null);
    }

    private RefundTarget applyReject(StoreOrder storeOrder, String reason) {
        storeOrder.requestReject();
        storeOrderTtlService.removeAutoReject(storeOrder.getId());
        return new RefundTarget(
                storeOrder.getOrder().getId(),
                storeOrder.getId(),
                storeOrder.getFinalPrice(),
                reason);
    }
}
