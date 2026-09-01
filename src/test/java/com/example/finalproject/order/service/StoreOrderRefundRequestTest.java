package com.example.finalproject.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.order.dto.request.PostOrderCancelRequest;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

class StoreOrderRefundRequestTest extends IntegrationTestSupport {

    @Autowired
    private StoreOrderRefundService storeOrderRefundService;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;

    @Test
    @DisplayName("고객 환불 요청은 주문 금액을 요청 금액으로 기록한다")
    void requestRefund_recordsRequestedAmount() {
        String email = newBuyerEmail();
        RefundTarget target = refundScenarioSeeder.deliveredStoreOrder(email);
        PostOrderCancelRequest request = new PostOrderCancelRequest();
        ReflectionTestUtils.setField(request, "reason", "단순 변심");

        storeOrderRefundService.requestRefund(email, target.storeOrderId(), request);

        PaymentRefund refund = paymentRefundRepository
                .findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()).get(0);
        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(refund.getRefundAmount())
                .as("요청 금액이 비어 있으면 관리자 집계가 항상 0 이 된다")
                .isEqualTo(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getFinalPrice());
        assertThat(refund.getRefundedAt()).isNull();
    }

    private String newBuyerEmail() {
        return "refund-request-" + System.nanoTime() + "@test.com";
    }
}
