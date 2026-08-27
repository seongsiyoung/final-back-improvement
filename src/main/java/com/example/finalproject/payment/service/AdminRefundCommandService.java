package com.example.finalproject.payment.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
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
    public void approve(Long refundId, PostPaymentRefundApproveRequest req) {

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

        // 상태 검증은 revertToRequested() 안에서 PG_REJECTED 여부로 이미 이뤄진다
        // (동일한 BusinessException(INVALID_REFUND_STATUS)을 던짐).
        refund.revertToRequested();
    }
}
