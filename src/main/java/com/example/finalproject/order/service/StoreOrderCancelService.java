package com.example.finalproject.order.service;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.payment.service.PaymentCancelService;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.user.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Slf4j
@Service
@RequiredArgsConstructor
public class StoreOrderCancelService {
    private final UserLoader userLoader;
    private final PaymentCancelService paymentCancelService;
    private final StoreOrderStatusService storeOrderStatusService;

    public void cancelStoreOrder(String email, Long storeOrderId, String reason) {
        log.info("[STORE_ORDER_CANCEL_REQUEST] email={}, storeOrderId={}, reason={}", email, storeOrderId, reason);

        User user = userLoader.loadUserByUsername(email);

        RefundTarget target = storeOrderStatusService.requestCancel(storeOrderId, user.getId(), reason);
        paymentCancelService.cancel(target);
    }
}
