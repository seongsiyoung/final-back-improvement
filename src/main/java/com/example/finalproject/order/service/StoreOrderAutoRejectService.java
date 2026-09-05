package com.example.finalproject.order.service;

import com.example.finalproject.payment.service.RefundTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreOrderAutoRejectService {

    private final StoreOrderRejectCommandService rejectCommandService;
    private final StoreOrderRejectService rejectService;

    public void rejectSingleOrder(Long storeOrderId) {
        RefundTarget target = rejectCommandService.requestRejectIfPending(storeOrderId, "자동 거절 (미응답)");
        if (target != null) {
            rejectService.reject(target);
        }
    }
}
