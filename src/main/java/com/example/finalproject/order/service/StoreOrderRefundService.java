package com.example.finalproject.order.service;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.order.dto.request.PostOrderCancelRequest;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.user.domain.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StoreOrderRefundService {

    private final UserLoader userLoader;
    private final StoreOrderRepository storeOrderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRefundRepository refundRepository;

    @Transactional
    public void requestRefund(String email, Long storeOrderId, PostOrderCancelRequest request) {

        User user = userLoader.loadUserByUsername(email);

        StoreOrder storeOrder = storeOrderRepository.findById(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));

        if (!storeOrder.getOrder().getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        if (refundRepository.findActiveByStoreOrderId(storeOrderId).isPresent()) {
            throw new BusinessException(ErrorCode.REFUND_ALREADY_REQUESTED);
        }

        storeOrder.validateRefundRequestable();

        Long orderId = storeOrder.getOrder().getId();
        Payment payment = paymentRepository.findByOrder_Id(orderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        PaymentRefund refund = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(storeOrder.getFinalPrice())
                .refundReason(request.getReason())
                .refundStatus(RefundStatus.REQUESTED)
                .build();

        refundRepository.save(refund);

        storeOrder.requestRefund(request.getReason());
    }
}