package com.example.finalproject.order.service;

import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.service.PaymentCancelService;
import com.example.finalproject.payment.service.RefundTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreOrderAutoRejectService {

    private final StoreOrderRepository storeOrderRepository;
    private final StoreOrderTtlService storeOrderTtlService;
    private final PaymentCancelService paymentCancelService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void rejectSingleOrder(Long storeOrderId) {
        StoreOrder storeOrder = storeOrderRepository.findById(storeOrderId)
                .orElseThrow(() -> new IllegalStateException("주문을 찾을 수 없습니다: " + storeOrderId));

        storeOrder.requestReject();
        storeOrderTtlService.removeAutoReject(storeOrderId);
        paymentCancelService.cancel(new RefundTarget(
                storeOrder.getOrder().getId(), storeOrder.getId(), storeOrder.getFinalPrice(), "자동 거절 (미응답)"));
    }
}
