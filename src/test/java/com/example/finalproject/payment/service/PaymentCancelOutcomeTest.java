package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class PaymentCancelOutcomeTest extends IntegrationTestSupport {

    @Autowired
    private PaymentCancelService paymentCancelService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @MockBean
    private PaymentGateWay paymentGateWay;

    private final Request request = Request.create(
            HttpMethod.POST, "/v1/payments/x/cancel", Collections.emptyMap(),
            new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    private RuntimeException explicitRejection() {
        return new FeignException.BadRequest("bad request", request,
                "{\"code\":\"NOT_CANCELABLE_AMOUNT\"}".getBytes(StandardCharsets.UTF_8),
                Collections.emptyMap());
    }

    private RuntimeException resultUnknown() {
        return new RetryableException(-1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);
    }

    @Test
    @DisplayName("고객 취소가 PG에서 거절되면 주문은 PENDING 으로 돌아간다")
    void customerCancel_explicitRejection_revertsToPending() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(explicitRejection());

        assertThatThrownBy(() -> paymentCancelService.cancel(target))
                .isInstanceOf(BusinessException.class);

        assertThat(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.PENDING);
        assertThat(paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.APPROVED);
        assertThat(paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()).get(0).getRefundStatus())
                .isEqualTo(RefundStatus.PG_REJECTED);
    }

    @Test
    @DisplayName("고객 환불이 PG에서 거절되면 주문은 DELIVERED 로 돌아간다")
    void customerRefund_explicitRejection_revertsToDelivered() {
        RefundTarget target = refundScenarioSeeder.refundRequested(newBuyerEmail());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(explicitRejection());

        assertThatThrownBy(() -> paymentCancelService.cancel(target))
                .isInstanceOf(BusinessException.class);

        assertThat(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.DELIVERED);
        assertThat(paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("사장님 거절이 PG에서 거절되면 주문은 되돌리지 않고 결제만 확인 필요로 남는다")
    void storeReject_explicitRejection_keepsOrderAndFlagsPayment() {
        RefundTarget target = refundScenarioSeeder.rejectRequested(newBuyerEmail());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(explicitRejection());

        assertThatThrownBy(() -> paymentCancelService.cancel(target))
                .isInstanceOf(BusinessException.class);

        assertThat(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.REJECT_REQUESTED);
        assertThat(paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("결과를 알 수 없으면 아무것도 바꾸지 않고 PG_PENDING 으로 남긴다")
    void resultUnknown_keepsPgPending() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(resultUnknown());

        assertThatThrownBy(() -> paymentCancelService.cancel(target))
                .isInstanceOf(BusinessException.class);

        assertThat(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.CANCEL_REQUESTED);
        assertThat(paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.REFUND_REQUESTED);
        assertThat(paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow().getRefundStatus())
                .isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("호출이 나가지 않았어도 아무것도 바꾸지 않고 PG_PENDING 으로 남긴다 — 취소는 여전히 해야 한다")
    void notSent_keepsPgPending() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("toss-payment");
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(CallNotPermittedException.createCallNotPermittedException(breaker));

        assertThatThrownBy(() -> paymentCancelService.cancel(target))
                .isInstanceOf(BusinessException.class);

        assertThat(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.CANCEL_REQUESTED);
        assertThat(paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.REFUND_REQUESTED);
        assertThat(paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow().getRefundStatus())
                .isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("PG 취소가 성공하면 PG_APPROVED 를 거쳐 APPROVED 가 된다")
    void success_marksPgApprovedThenApproved() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new CancelResult(target.amount()));

        paymentCancelService.cancel(target);

        PaymentRefund refund = paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()).get(0);
        assertThat(refund.getRefundStatus()).isEqualTo(RefundStatus.APPROVED);
    }

    private String newBuyerEmail() {
        return "payment-cancel-outcome-" + System.nanoTime() + "@test.com";
    }
}
