package com.example.finalproject.order.service;

import com.example.finalproject.payment.service.PaymentCancelService;
import com.example.finalproject.payment.service.RefundTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreOrderRejectService {

    private final PaymentCancelService paymentCancelService;

    public void reject(RefundTarget target) {
        log.info("[STORE_ORDER_REJECT] storeOrderId={}, reason={}",
                target.storeOrderId(), target.reason());
        paymentCancelService.cancel(target);
    }
}
