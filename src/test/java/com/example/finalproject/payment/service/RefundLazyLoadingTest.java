package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.order.service.StoreOrderCancelService;
import com.example.finalproject.payment.dto.request.PostPaymentRefundApproveRequest;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

class RefundLazyLoadingTest extends IntegrationTestSupport {

    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private StoreOrderCancelService storeOrderCancelService;
    @Autowired
    private AdminRefundService adminRefundService;
    @MockBean
    private PaymentGateWay paymentGateWay;

    @Test
    @DisplayName("트랜잭션 밖에서 LAZY 연관을 두 홉 건너면 터진다")
    void lazyAssociationOutsideTransaction_throws() {
        Long storeOrderId = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail()).storeOrderId();

        StoreOrder detached = storeOrderRepository.findById(storeOrderId).orElseThrow();

        assertThatThrownBy(() -> detached.getOrder().getUser().getId())
                .isInstanceOf(LazyInitializationException.class);
    }

    @Test
    @DisplayName("고객 주문 취소가 트랜잭션 밖 LAZY 접근으로 터지지 않는다")
    void cancelStoreOrder_doesNotTouchLazyAssociationOutsideTransaction() {
        String email = newBuyerEmail();
        Long storeOrderId = refundScenarioSeeder.approvedWithPendingStoreOrder(email).storeOrderId();
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new CancelResult(3000));

        assertThatCode(() -> storeOrderCancelService.cancelStoreOrder(email, storeOrderId, "고객 변심"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("관리자 환불 승인이 트랜잭션 밖 LAZY 접근으로 터지지 않는다")
    void approveAndCancel_doesNotTouchLazyAssociationOutsideTransaction() {
        Long storeOrderId = refundScenarioSeeder.refundRequested(newBuyerEmail()).storeOrderId();
        Long refundId = paymentRefundRepository.findByStoreOrder_Id(storeOrderId).orElseThrow().getId();
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new CancelResult(3000));
        PostPaymentRefundApproveRequest request = new PostPaymentRefundApproveRequest();
        ReflectionTestUtils.setField(request, "responsibility", RefundResponsibility.CUSTOMER);

        assertThatCode(() -> adminRefundService.approveAndCancel(refundId, request))
                .doesNotThrowAnyException();
    }

    private String newBuyerEmail() {
        return "refund-scenario-buyer-" + System.nanoTime() + "@test.com";
    }
}
