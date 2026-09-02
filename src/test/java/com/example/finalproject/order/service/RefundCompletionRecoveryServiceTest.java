package com.example.finalproject.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RefundCompletionRecoveryServiceTest extends IntegrationTestSupport {

    @Autowired
    private RefundCompletionRecoveryService refundCompletionRecoveryService;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("환불은 끝났는데 주문이 요청 상태로 남은 건을 되살린다")
    void recover_completesLostFollowUp() {
        RefundTarget target = refundScenarioSeeder.refundCompletionLost(newBuyerEmail());
        List<Integer> stockBefore = stockOf(target.storeOrderId());

        refundCompletionRecoveryService.recover(storeOrderOf(target));

        assertThat(storeOrderOf(target).getStatus()).isEqualTo(StoreOrderStatus.CANCELLED);
        assertThat(stockOf(target.storeOrderId()))
                .as("유실된 후속 처리의 핵심은 복구되지 않은 재고다")
                .isNotEmpty()
                .isEqualTo(stockBefore.stream().map(stock -> stock + 1).toList());
    }

    @Test
    @DisplayName("두 번 실행해도 재고가 두 번 복구되지 않는다")
    void recover_isIdempotent() {
        RefundTarget target = refundScenarioSeeder.refundCompletionLost(newBuyerEmail());
        refundCompletionRecoveryService.recover(storeOrderOf(target));
        List<Integer> stockAfterFirst = stockOf(target.storeOrderId());

        refundCompletionRecoveryService.recover(storeOrderOf(target));

        assertThat(stockOf(target.storeOrderId()))
                .as("스캔은 같은 건을 여러 주기에 걸쳐 다시 집을 수 있다")
                .isEqualTo(stockAfterFirst);
    }

    @Test
    @DisplayName("환불 사유를 주문의 취소 사유로 옮긴다")
    void recover_carriesRefundReason() {
        RefundTarget target = refundScenarioSeeder.refundCompletionLost(newBuyerEmail());

        refundCompletionRecoveryService.recover(storeOrderOf(target));

        assertThat(storeOrderOf(target).getCancelReason()).isEqualTo(target.reason());
    }

    @Test
    @DisplayName("승인된 환불 이력이 없으면 취소로 확정하지 않는다")
    void recover_skipsStoreOrderWithoutApprovedRefund() {
        RefundTarget target = refundScenarioSeeder.refundCompletionLostWithoutRefundHistory(newBuyerEmail());
        List<Integer> stockBefore = stockOf(target.storeOrderId());

        refundCompletionRecoveryService.recover(storeOrderOf(target));

        assertThat(storeOrderOf(target).getStatus())
                .as("같은 주문의 다른 매장이 환불돼 대상에 섞인 건이다. 이 매장의 돈은 그대로다")
                .isEqualTo(StoreOrderStatus.CANCEL_REQUESTED);
        assertThat(stockOf(target.storeOrderId()))
                .as("환불 없이 재고만 늘어나면 조용한 금전 불일치가 된다")
                .isEqualTo(stockBefore);
    }

    @Test
    @DisplayName("거절 경로의 유실도 같은 방식으로 되살린다")
    void recover_completesLostRejectFollowUp() {
        RefundTarget target = refundScenarioSeeder.rejectCompletionLost(newBuyerEmail());
        List<Integer> stockBefore = stockOf(target.storeOrderId());

        refundCompletionRecoveryService.recover(storeOrderOf(target));

        assertThat(storeOrderOf(target).getStatus()).isEqualTo(StoreOrderStatus.REJECTED);
        assertThat(stockOf(target.storeOrderId()))
                .isEqualTo(stockBefore.stream().map(stock -> stock + 1).toList());
    }

    @Test
    @DisplayName("환불 이력이 여러 건이면 가장 최근 건의 사유를 쓴다")
    void recover_usesLatestRefundReason() {
        RefundTarget target = refundScenarioSeeder.refundCompletionLost(newBuyerEmail());
        refundScenarioSeeder.addOlderRejectedRefund(target, "이전 시도");

        refundCompletionRecoveryService.recover(storeOrderOf(target));

        assertThat(storeOrderOf(target).getStatus()).isEqualTo(StoreOrderStatus.CANCELLED);
        assertThat(storeOrderOf(target).getCancelReason()).isEqualTo(target.reason());
    }

    private com.example.finalproject.order.domain.StoreOrder storeOrderOf(RefundTarget target) {
        return storeOrderRepository.findById(target.storeOrderId()).orElseThrow();
    }

    private List<Integer> stockOf(Long storeOrderId) {
        return jdbcTemplate.queryForList(
                "select p.stock from order_products op join products p on p.id = op.product_id "
                        + "where op.store_order_id = ? order by op.id",
                Integer.class,
                storeOrderId);
    }

    private String newBuyerEmail() {
        return "refund-recovery-" + System.nanoTime() + "@test.com";
    }
}
