package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.dto.request.TossBillingApproveRequest;
import com.example.finalproject.payment.dto.response.TossBillingApproveResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.PassThroughCircuitBreakerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SubscriptionBillingServiceTest {

    private TossPaymentsClient tossPaymentsClient;
    private SubscriptionChargeCommandService subscriptionChargeCommandService;
    private PaymentGateWay paymentGateWay;
    private SubscriptionBillingService subscriptionBillingService;

    @BeforeEach
    void setUp() {
        tossPaymentsClient = mock(TossPaymentsClient.class);
        subscriptionChargeCommandService = mock(SubscriptionChargeCommandService.class);
        paymentGateWay = mock(PaymentGateWay.class);

        subscriptionBillingService = new SubscriptionBillingService(
                tossPaymentsClient, subscriptionChargeCommandService, paymentGateWay,
                PassThroughCircuitBreakerFactory.create());
    }

    private TossBillingApproveResponse approveResponse(String paymentKey) throws Exception {
        String json = """
                {
                  "paymentKey": "%s", "orderId": "order-1", "orderName": "구독",
                  "status": "DONE", "approvedAt": "2026-08-20T00:00:00+09:00",
                  "card": { "issuerCode": "61", "acquirerCode": "31", "number": "1234", "cardType": "credit", "ownerType": "personal" }
                }
                """.formatted(paymentKey);
        return new ObjectMapper().readValue(json, TossBillingApproveResponse.class);
    }

    private SubscriptionPayment pendingPayment(Long id, int amount) {
        SubscriptionPayment pending = SubscriptionPayment.builder()
                .paymentMethod(PaymentMethodType.CARD)
                .amount(amount)
                .pgOrderId("SUB-1-" + id)
                .pgProvider("TOSS")
                .build();
        ReflectionTestUtils.setField(pending, "id", id);
        return pending;
    }

    @Test
    void chargeMonthlyFee_delegatesToCommandService_andReturnsCompleted() throws Exception {
        SubscriptionPayment pending = pendingPayment(1L, 15000);
        TossBillingApproveRequest request = TossBillingApproveRequest.builder().build();
        when(subscriptionChargeCommandService.startCharge(10L))
                .thenReturn(new SubscriptionChargeCommandService.ChargeStart(pending, request, "plain-billing-key", LocalDate.of(2026, 8, 21)));

        TossBillingApproveResponse response = approveResponse("pk-1");
        when(tossPaymentsClient.approveBilling(eq("plain-billing-key"), any(), any())).thenReturn(response);

        SubscriptionPayment completed = pendingPayment(1L, 15000);
        when(subscriptionChargeCommandService.completeCharge(1L, response)).thenReturn(completed);

        SubscriptionPayment result = subscriptionBillingService.chargeMonthlyFee(10L);

        org.assertj.core.api.Assertions.assertThat(result).isSameAs(completed);
        verify(subscriptionChargeCommandService).completeCharge(1L, response);
    }

    @Test
    void chargeMonthlyFee_whenApproveIsExplicitlyRejected_marksChargeFailed_withoutCancellation() {
        SubscriptionPayment pending = pendingPayment(1L, 15000);
        TossBillingApproveRequest request = TossBillingApproveRequest.builder().build();
        when(subscriptionChargeCommandService.startCharge(10L))
                .thenReturn(new SubscriptionChargeCommandService.ChargeStart(pending, request, "plain-billing-key", LocalDate.of(2026, 8, 21)));

        when(tossPaymentsClient.approveBilling(eq("plain-billing-key"), any(), any()))
                .thenThrow(new FeignException.BadRequest("bad request",
                        Request.create(HttpMethod.POST, "/v1/billing/x", Collections.emptyMap(), new byte[0],
                                StandardCharsets.UTF_8, new RequestTemplate()),
                        "{\"code\":\"REJECT_CARD_COMPANY\"}".getBytes(StandardCharsets.UTF_8),
                        Collections.emptyMap()));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> subscriptionBillingService.chargeMonthlyFee(10L));

        verify(subscriptionChargeCommandService).failCharge(1L);
        verify(paymentGateWay, org.mockito.Mockito.never()).cancel(any(), org.mockito.ArgumentMatchers.anyInt(), any(), any());
    }

    @Test
    void chargeMonthlyFee_whenCompleteChargeFails_cancelsPgApprovalAndFailsReversalPending() throws Exception {
        SubscriptionPayment pending = pendingPayment(1L, 15000);
        TossBillingApproveRequest request = TossBillingApproveRequest.builder().build();
        when(subscriptionChargeCommandService.startCharge(10L))
                .thenReturn(new SubscriptionChargeCommandService.ChargeStart(pending, request, "plain-billing-key", LocalDate.of(2026, 8, 21)));

        TossBillingApproveResponse response = approveResponse("pk-1");
        when(tossPaymentsClient.approveBilling(eq("plain-billing-key"), any(), any())).thenReturn(response);
        when(subscriptionChargeCommandService.completeCharge(1L, response))
                .thenThrow(new RuntimeException("DB 반영 실패"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> subscriptionBillingService.chargeMonthlyFee(10L));

        verify(paymentGateWay).cancel(eq("pk-1"), eq(15000), eq("구독 결제 반영 실패로 인한 취소"), any());
        verify(subscriptionChargeCommandService).markReversalPending(1L);
        verify(subscriptionChargeCommandService).failReversalPending(1L);
    }

    @Test
    void chargeMonthlyFee_whenCompleteChargeAndCancelBothFail_preservesOriginalException_andKeepsReversalPending()
            throws Exception {
        SubscriptionPayment pending = pendingPayment(1L, 15000);
        TossBillingApproveRequest request = TossBillingApproveRequest.builder().build();
        when(subscriptionChargeCommandService.startCharge(10L))
                .thenReturn(new SubscriptionChargeCommandService.ChargeStart(pending, request, "plain-billing-key", LocalDate.of(2026, 8, 21)));

        TossBillingApproveResponse response = approveResponse("pk-1");
        when(tossPaymentsClient.approveBilling(eq("plain-billing-key"), any(), any())).thenReturn(response);
        RuntimeException dbFailure = new RuntimeException("DB 반영 실패");
        when(subscriptionChargeCommandService.completeCharge(1L, response)).thenThrow(dbFailure);
        RuntimeException cancelFailure = new RuntimeException("PG 취소도 실패");
        org.mockito.Mockito.doThrow(cancelFailure)
                .when(paymentGateWay).cancel(eq("pk-1"), eq(15000), eq("구독 결제 반영 실패로 인한 취소"), any());

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> subscriptionBillingService.chargeMonthlyFee(10L));

        // 취소 보상 실패가 원래 원인(DB 반영 실패)을 대체하지 않아야 한다.
        org.assertj.core.api.Assertions.assertThat(thrown).isSameAs(dbFailure);
        org.assertj.core.api.Assertions.assertThat(thrown.getSuppressed()).contains(cancelFailure);
        verify(subscriptionChargeCommandService).markReversalPending(1L);
        verify(subscriptionChargeCommandService, org.mockito.Mockito.never()).failReversalPending(1L);
    }
}
