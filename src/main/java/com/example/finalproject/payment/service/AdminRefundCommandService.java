package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.dto.request.PostPaymentRefundApproveRequest;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.util.RefundAmountCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminRefundCommandService {

    private final PaymentRefundRepository refundRepository;
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

    @Transactional
    public void retry(Long refundId) {

        PaymentRefund refund = refundRepository.findById(refundId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REFUND_NOT_FOUND));

        // 고객 주문 취소가 PG 에서 거절돼도 같은 주문에 PG_REJECTED 행이 남는다.
        // 그 주문은 PENDING 으로 되살아나 사장님 접수를 기다리는 상태다.
        // 환불 재시도로 되살리면 취소가 환불로 바뀐다.
        if (refund.getStoreOrder().getStatus() != StoreOrderStatus.REFUND_REQUESTED) {
            throw new BusinessException(ErrorCode.INVALID_STORE_ORDER_REFUND_STATUS);
        }

        // 환불 상태 검증은 revertToRequested() 안에서 PG_REJECTED 여부로 이미 이뤄진다
        // (동일한 BusinessException(INVALID_REFUND_STATUS)을 던짐).
        refund.revertToRequested();
    }
}
