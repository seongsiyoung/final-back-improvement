package com.example.finalproject.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 이 테스트는 클래스/메서드 레벨 {@code @Transactional}을 쓰지 않는다. 스케줄러가 트랜잭션
 * 없이 돌면서 건별로 독립 트랜잭션을 커밋한다는 것이 검증 대상이기 때문이다.
 *
 * <p>통합 테스트가 DB를 공유하므로 각 테스트는 hideAllReconciliationTargets 로 다른
 * 테스트가 남긴 행을 시간 경계 밖으로 밀어낸 뒤, 자기 대상만 다시 노출시킨다.
 */
class ReconciliationSchedulerTest extends IntegrationTestSupport {

    @Autowired
    private RefundReconciliationScheduler refundReconciliationScheduler;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @MockBean
    private TossPaymentsClient tossPaymentsClient;
    @MockBean
    private PaymentGateWay paymentGateWay;

    @Test
    @DisplayName("스케줄러는 PG 호출을 트랜잭션 밖에서 한다")
    void reconcileStuckRefunds_callsPgOutsideTransaction() {
        RefundTarget target = only(refundScenarioSeeder.stuckInPgPending(email()));
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return response(target.amount(), 0);
        });

        refundReconciliationScheduler.reconcileStuckRefunds();

        assertThat(transactionActive)
                .as("이 저장소의 최상위 규약은 PG 호출을 트랜잭션 안에서 하지 않는 것이다")
                .isFalse();
        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.APPROVED);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지를 계속 처리한다")
    void reconcileStuckRefunds_continuesAfterOneFailure() {
        RefundTarget failing = refundScenarioSeeder.stuckInPgPending(email());
        RefundTarget healthy = refundScenarioSeeder.stuckInPgPending(email());
        refundScenarioSeeder.hideAllReconciliationTargets();
        refundScenarioSeeder.exposeReconciliationTarget(failing);
        refundScenarioSeeder.exposeReconciliationTarget(healthy);

        when(tossPaymentsClient.getPaymentByOrderId(eq(refundScenarioSeeder.pgOrderIdOf(failing))))
                .thenThrow(new RuntimeException("Toss 조회 실패"));
        when(tossPaymentsClient.getPaymentByOrderId(eq(refundScenarioSeeder.pgOrderIdOf(healthy))))
                .thenReturn(response(healthy.amount(), 0));

        refundReconciliationScheduler.reconcileStuckRefunds();

        assertThat(latestRefundStatus(healthy)).isEqualTo(RefundStatus.APPROVED);
        assertThat(latestRefundStatus(failing))
                .as("실패한 건은 상태가 남아 다음 주기에 다시 잡힌다")
                .isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("최근에 상태가 바뀐 건은 같은 주기에 다시 집지 않는다")
    void reconcileStuckRefunds_ignoresRecentTargets() {
        RefundTarget target = refundScenarioSeeder.stuckInPgPending(email());
        refundScenarioSeeder.hideAllReconciliationTargets();
        // 스텁을 채워 둔다. 비워 두면 시간 필터를 지워도 null 응답이 NPE 로 삼켜져 테스트가 통과한다.
        when(tossPaymentsClient.getPaymentByOrderId(anyString())).thenReturn(response(target.amount(), 0));

        refundReconciliationScheduler.reconcileStuckRefunds();

        verify(tossPaymentsClient, never()).getPaymentByOrderId(anyString());
        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.PG_PENDING);
    }

    @Test
    @DisplayName("유실된 후속 처리를 스캔이 집어내 복구한다")
    void recoverLostRefundCompletions_completesScannedTarget() {
        RefundTarget target = only(refundScenarioSeeder.refundCompletionLost(email()));

        refundReconciliationScheduler.recoverLostRefundCompletions();

        assertThat(storeOrderStatus(target))
                .as("조회가 이 조합을 집지 못하면 복구 서비스는 영영 호출되지 않는다")
                .isEqualTo(StoreOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("복구할 수 없는 건이 큐 앞에 있어도 뒤의 건을 복구한다")
    void recoverLostRefundCompletions_skipsUnrecoverableAndContinues() {
        RefundTarget unrecoverable = refundScenarioSeeder.refundCompletionLostWithoutRefundHistory(email());
        RefundTarget healthy = refundScenarioSeeder.refundCompletionLost(email());
        refundScenarioSeeder.hideAllReconciliationTargets();
        refundScenarioSeeder.exposeReconciliationTarget(unrecoverable, 40);
        refundScenarioSeeder.exposeReconciliationTarget(healthy, 30);

        refundReconciliationScheduler.recoverLostRefundCompletions();

        assertThat(storeOrderStatus(healthy)).isEqualTo(StoreOrderStatus.CANCELLED);
        assertThat(storeOrderStatus(unrecoverable))
                .as("승인된 환불 이력이 없는 건은 상태가 움직이지 않아 매 주기 다시 잡힌다")
                .isEqualTo(StoreOrderStatus.CANCEL_REQUESTED);
    }

    /** 다른 테스트가 남긴 행을 숨기고 이 대상만 스캔에 노출한다. */
    private RefundTarget only(RefundTarget target) {
        refundScenarioSeeder.hideAllReconciliationTargets();
        refundScenarioSeeder.exposeReconciliationTarget(target);
        return target;
    }

    private StoreOrderStatus storeOrderStatus(RefundTarget target) {
        return storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus();
    }

    private RefundStatus latestRefundStatus(RefundTarget target) {
        return paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId())
                .getFirst().getRefundStatus();
    }

    private TossConfirmResponse response(int total, int balance) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "totalAmount", total);
        ReflectionTestUtils.setField(response, "balanceAmount", balance);
        return response;
    }

    private String email() {
        return "reconcile-scheduler-" + System.nanoTime() + "@test.com";
    }
}
