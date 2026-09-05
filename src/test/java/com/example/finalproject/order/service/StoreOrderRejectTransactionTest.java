package com.example.finalproject.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;

class StoreOrderRejectTransactionTest extends IntegrationTestSupport {

    @Autowired
    private StoreOrderRejectService storeOrderRejectService;
    @Autowired
    private StoreOrderService storeOrderService;
    @Autowired
    private StoreOrderRejectCommandService rejectCommandService;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private PaymentGateWay paymentGateWay;

    @Test
    @DisplayName("PG 취소가 거절돼도 주문은 PENDING 으로 돌아가지 않는다")
    void reject_whenPgRejects_doesNotRevertToPending() {
        RefundTarget seed = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());
        Request request = Request.create(HttpMethod.POST, "/cancel", Collections.emptyMap(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new FeignException.BadRequest("bad request", request,
                        "{\"code\":\"NOT_CANCELABLE_AMOUNT\"}".getBytes(StandardCharsets.UTF_8),
                        Collections.emptyMap()));

        RefundTarget target = rejectCommandService.requestReject(seed.storeOrderId(), "자동 거절 (미응답)");

        assertThatThrownBy(() -> storeOrderRejectService.reject(target))
                .isInstanceOf(BusinessException.class);

        assertThat(storeOrderRepository.findById(seed.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.REJECT_REQUESTED);
        assertThat(paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    @DisplayName("requestReject 는 PG 호출 전에 커밋된다")
    void requestReject_commitsBeforePgCall() {
        RefundTarget seed = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());

        rejectCommandService.requestReject(seed.storeOrderId(), "자동 거절 (미응답)");

        assertThat(storeOrderRepository.findById(seed.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.REJECT_REQUESTED);
    }

    @Test
    @DisplayName("스케줄 자동 거절은 PG 호출 전에 트랜잭션을 끝낸다")
    void scheduledAutoReject_callsPgOutsideTransaction() {
        RefundTarget seed = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());
        jdbcTemplate.update("update store_orders set created_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(6), seed.storeOrderId());
        AtomicBoolean transactionActiveAtPgCall = new AtomicBoolean(true);
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    transactionActiveAtPgCall.set(TransactionSynchronizationManager.isActualTransactionActive());
                    return new CancelResult(seed.amount());
                });

        storeOrderService.processTimedOutStoreOrders();

        assertThat(transactionActiveAtPgCall).isFalse();
    }

    private String newBuyerEmail() {
        return "reject-transaction-" + System.nanoTime() + "@test.com";
    }
}
