package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class RefundHistoryTest extends IntegrationTestSupport {

    @Autowired
    private PaymentCancelService paymentCancelService;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @MockBean
    private PaymentGateWay paymentGateWay;

    private RuntimeException explicitRejection() {
        Request request = Request.create(HttpMethod.POST, "/v1/payments/x/cancel", Collections.emptyMap(),
                new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        return new FeignException.BadRequest("bad request", request,
                "{\"code\":\"NOT_CANCELABLE_AMOUNT\"}".getBytes(StandardCharsets.UTF_8),
                Collections.emptyMap());
    }

    @Test
    @DisplayName("PG 거절로 종결된 환불이 있어도 같은 주문을 다시 취소할 수 있다")
    void closedRefund_doesNotBlockRetry() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(explicitRejection());

        assertThatThrownBy(() -> paymentCancelService.cancel(target))
                .isInstanceOf(BusinessException.class);

        RefundTarget retry = refundScenarioSeeder.requestCancelAgain(target);
        // 이미 예외를 던지도록 스텁된 목은 when(mock.method(...)) 로 다시 스텁할 수 없다.
        // 스텁하려고 메서드를 호출하는 순간 앞의 예외가 터진다.
        doReturn(new CancelResult(retry.amount()))
                .when(paymentGateWay).cancel(anyString(), anyInt(), anyString(), anyString());

        paymentCancelService.cancel(retry);

        List<PaymentRefund> history =
                paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId());
        assertThat(history).hasSize(2);
        assertThat(history.stream().map(PaymentRefund::getRefundStatus))
                .as("종결된 이력을 덮어쓰지 않는다")
                .containsExactlyInAnyOrder(RefundStatus.APPROVED, RefundStatus.PG_REJECTED);
    }

    @Test
    @DisplayName("활성 건 조회는 종결된 이력을 무시한다")
    void findActive_ignoresClosedHistory() {
        RefundTarget target = refundScenarioSeeder.withClosedRefundHistory(newBuyerEmail());

        assertThat(paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()))
                .isEmpty();
        assertThat(paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()))
                .hasSize(1);
    }

    @Test
    @DisplayName("진행 중인 환불이 있으면 활성 건으로 찾힌다")
    void findActive_findsInFlightRefund() {
        RefundTarget target = refundScenarioSeeder.refundRequested(newBuyerEmail());

        assertThat(paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()))
                .isPresent()
                .get()
                .extracting(PaymentRefund::getRefundStatus)
                .isEqualTo(RefundStatus.REQUESTED);
    }

    private String newBuyerEmail() {
        return "refund-history-" + System.nanoTime() + "@test.com";
    }
}
