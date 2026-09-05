package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

class RefundReconciliationServiceTest extends IntegrationTestSupport {
    @Autowired private RefundReconciliationService refundReconciliationService;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundScenarioSeeder refundScenarioSeeder;
    @MockBean private TossPaymentsClient tossPaymentsClient;
    @MockBean private PaymentGateWay paymentGateWay;

    @Test
    @DisplayName("PG가 이미 취소했으면 취소를 다시 보내지 않고 장부만 맞춘다")
    void pgPending_whenAlreadyCanceledAtPg_completesWithoutResending() {
        RefundTarget target = refundScenarioSeeder.stuckInPgPending(email());
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(response(3000, 0));
        refundReconciliationService.reconcile(active(target));
        verify(tossPaymentsClient).getPaymentByOrderId(anyString());
        verify(paymentGateWay, never()).cancel(anyString(), anyInt(), anyString(), anyString());
        assertThat(status(target)).isEqualTo(RefundStatus.APPROVED);
    }

    @Test
    @DisplayName("PG에 취소가 안 되어 있으면 취소를 다시 보낸다")
    void pgPending_whenNotCanceledAtPg_resendsCancel() {
        RefundTarget target = refundScenarioSeeder.stuckInPgPending(email());
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(response(3000, 3000));
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new CancelResult(target.amount()));
        refundReconciliationService.reconcile(active(target));
        verify(paymentGateWay).cancel(anyString(), anyInt(), anyString(), anyString());
        assertThat(status(target)).isEqualTo(RefundStatus.APPROVED);
    }

    @Test
    @DisplayName("Toss 조회가 실패하면 예외를 잡지 않고 상태도 바꾸지 않는다")
    void pgPending_whenQueryFails_keepsState() {
        RefundTarget target = refundScenarioSeeder.stuckInPgPending(email());
        PaymentRefund refund = active(target);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenThrow(new RuntimeException("조회 실패"));

        assertThatThrownBy(() -> refundReconciliationService.reconcile(refund))
                .isInstanceOf(RuntimeException.class);

        verify(paymentGateWay, never()).cancel(anyString(), anyInt(), anyString(), anyString());
        assertThat(status(target)).isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("PG_APPROVED 는 PG 호출 없이 장부 반영만 다시 한다")
    void pgApproved_retriesLedgerOnly() {
        RefundTarget target = refundScenarioSeeder.stuckInPgApproved(email());
        refundReconciliationService.reconcile(active(target));
        verify(tossPaymentsClient, never()).getPaymentByOrderId(anyString());
        verify(paymentGateWay, never()).cancel(anyString(), anyInt(), anyString(), anyString());
        assertThat(status(target)).isEqualTo(RefundStatus.APPROVED);
    }

    @Test
    @DisplayName("대상 상태가 아닌 환불은 아무것도 하지 않는다")
    void nonTargetStatus_doesNothing() {
        RefundTarget target = refundScenarioSeeder.refundRequested(email());

        refundReconciliationService.reconcile(active(target));

        verify(tossPaymentsClient, never()).getPaymentByOrderId(anyString());
        verify(paymentGateWay, never()).cancel(anyString(), anyInt(), anyString(), anyString());
        assertThat(status(target)).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    @DisplayName("장부가 이미 반영됐으면 예외를 던지지 않고 확정된 결제 상태도 덮지 않는다")
    void ledgerAlreadyApplied_returnsQuietly() {
        RefundTarget target = refundScenarioSeeder.stuckInPgApproved(email());
        PaymentRefund stale = active(target);
        refundReconciliationService.reconcile(stale);

        // 같은 낡은 엔티티로 재실행한다. applyRefund 는 INVALID_PAYMENT_CANCEL_STATUS 로 실패한다.
        refundReconciliationService.reconcile(stale);

        assertThat(status(target)).isEqualTo(RefundStatus.APPROVED);
        assertThat(paymentStatus(target)).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    @DisplayName("장부가 반영되지 않은 규칙 위반은 확인 필요로 남기고 예외를 다시 던진다")
    void ledgerRuleError_marksReconciliationRequired() {
        RefundTarget target = refundScenarioSeeder.stuckInPgApproved(email());
        refundScenarioSeeder.forceFullyRefundedAmount(target);
        PaymentRefund refund = active(target);

        assertThatThrownBy(() -> refundReconciliationService.reconcile(refund))
                .isInstanceOf(BusinessException.class);

        assertThat(status(target)).isEqualTo(RefundStatus.RECONCILIATION_REQUIRED);
        assertThat(paymentStatus(target)).isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
    }

    private PaymentRefund active(RefundTarget target) { return paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow(); }
    private RefundStatus status(RefundTarget target) { return paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId()).getFirst().getRefundStatus(); }
    private TossConfirmResponse response(int total, int balance) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "totalAmount", total);
        ReflectionTestUtils.setField(response, "balanceAmount", balance);
        return response;
    }
    private PaymentStatus paymentStatus(RefundTarget target) { return paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus(); }
    private String email() { return "refund-reconcile-" + System.nanoTime() + "@test.com"; }
}
