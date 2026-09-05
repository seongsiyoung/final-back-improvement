package com.example.finalproject.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
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

class RefundCompletionIdempotencyTest extends IntegrationTestSupport {

    @Autowired
    private StoreOrderStatusService storeOrderStatusService;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("이미 취소 완료된 주문에 다시 실행해도 조용히 끝난다")
    void alreadyCancelled_isNoOp() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        storeOrderStatusService.handleRefundCompletion(target.storeOrderId(), "고객 변심");

        assertThatCode(() -> storeOrderStatusService.handleRefundCompletion(target.storeOrderId(), "고객 변심"))
                .as("리스너와 스케줄러가 같은 건을 잡으면 두 번 불린다")
                .doesNotThrowAnyException();

        assertThat(storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("재실행해도 재고가 두 번 복구되지 않는다")
    void alreadyCancelled_doesNotRestoreStockTwice() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());
        List<Integer> stockBeforeCompletion = stockOf(target.storeOrderId());

        storeOrderStatusService.handleRefundCompletion(target.storeOrderId(), "고객 변심");
        List<Integer> stockAfterFirst = stockOf(target.storeOrderId());

        assertThat(stockAfterFirst)
                .as("첫 환불 완료 처리에서 주문 상품 재고를 복구한다")
                .isNotEmpty()
                .isEqualTo(stockBeforeCompletion.stream().map(stock -> stock + 1).toList());

        storeOrderStatusService.handleRefundCompletion(target.storeOrderId(), "고객 변심");

        assertThat(stockOf(target.storeOrderId()))
                .as("재고 이중 복구는 오류도 로그도 없이 상품 수량을 부풀린다")
                .isEqualTo(stockAfterFirst);
    }

    @Test
    @DisplayName("후속 처리 대상이 아닌 상태는 조용히 넘기지 않는다")
    void unrelatedStatus_stillThrows() {
        RefundTarget target = refundScenarioSeeder.approvedWithPendingStoreOrder(newBuyerEmail());

        assertThatThrownBy(() -> storeOrderStatusService.handleRefundCompletion(target.storeOrderId(), "고객 변심"))
                .as("PENDING 주문에 환불 완료 처리가 들어오면 무언가 잘못된 것이다")
                .isInstanceOf(BusinessException.class);
    }

    private List<Integer> stockOf(Long storeOrderId) {
        return jdbcTemplate.queryForList(
                "select p.stock from order_products op join products p on p.id = op.product_id "
                        + "where op.store_order_id = ? order by op.id",
                Integer.class,
                storeOrderId);
    }

    private String newBuyerEmail() {
        return "refund-completion-" + System.nanoTime() + "@test.com";
    }
}
