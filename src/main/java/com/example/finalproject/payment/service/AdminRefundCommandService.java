package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.dto.request.PostPaymentRefundApproveRequest;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.util.RefundAmountCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRefundCommandService {

    private final PaymentRefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final RefundAmountCalculator refundAmountCalculator;

    @Transactional
    public RefundTarget approve(Long refundId, PostPaymentRefundApproveRequest req) {

        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));

        if (refund.getRefundStatus() != RefundStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }

        int refundAmount = refundAmountCalculator.calculate(
                refund.getStoreOrder(),
                req.getResponsibility()
        );

        refund.confirmRefundDetails(req.getResponsibility(), refundAmount);

        return new RefundTarget(
                refund.getStoreOrder().getOrder().getId(),
                refund.getStoreOrder().getId(),
                refundAmount,
                refund.getRefundReason());
    }

    @Transactional
    public void reject(Long refundId) {

        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));

        if (refund.getRefundStatus() != RefundStatus.REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }

        refund.adminReject();
        refund.getStoreOrder().revertRefundRequest();
    }

    /**
     * PG 가 거절한 환불을 다시 시도한다.
     *
     * <p>종결된 이력을 되살리지 않고 새 환불 시도를 만든다. PG_REJECTED 는 그 시도가
     * 끝났다는 기록이고, 덮어쓰면 거절이 있었다는 사실이 사라진다.
     *
     * <p>고객 환불 갈래만 다시 시도한다. 고객 주문 취소가 PG 에서 거절되면 주문이
     * PENDING 으로 되살아나는데, 그건 사장님 접수를 기다리는 상태지 환불 대상이 아니다.
     * 거기서 환불을 다시 걸면 취소가 환불로 바뀐다.
     */
    @Transactional
    public void retry(Long refundId) {

        PaymentRefund rejected = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));

        if (rejected.getRefundStatus() != RefundStatus.PG_REJECTED) {
            throw new BusinessException(ErrorCode.INVALID_REFUND_STATUS);
        }

        StoreOrder storeOrder = rejected.getStoreOrder();
        if (storeOrder.getStatus() != StoreOrderStatus.DELIVERED) {
            throw new BusinessException(ErrorCode.INVALID_STORE_ORDER_REFUND_STATUS);
        }

        // 활성 건 검사보다 먼저 Payment 행에 락을 잡는다.
        // 한 주문에 진행 중인 환불이 하나임을 지키는 유일한 장치다.
        Payment payment = paymentRepository.findWithLockByOrder_Id(storeOrder.getOrder().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (refundRepository.findActiveByStoreOrderId(storeOrder.getId()).isPresent()) {
            throw new BusinessException(ErrorCode.REFUND_ALREADY_REQUESTED);
        }

        storeOrder.requestRefund(rejected.getRefundReason());

        refundRepository.save(PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(storeOrder.getFinalPrice())
                .refundReason(rejected.getRefundReason())
                .refundStatus(RefundStatus.REQUESTED)
                .build());
    }
}
