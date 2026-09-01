package com.example.finalproject.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RefundAggregationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;

    @Test
    @DisplayName("주문별 환불 합계는 승인된 건만 더한다")
    void sumByStoreOrder_countsApprovedOnly() {
        RefundTarget target = refundScenarioSeeder.approvedAndRejectedRefundHistory(newBuyerEmail());

        List<Object[]> rows = paymentRefundRepository.sumRefundAmountGroupByStoreOrderId(
                List.of(target.storeOrderId()), RefundStatus.APPROVED);

        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0)[1]).longValue())
                .as("거절된 환불 금액이 합계에 들어가면 안 된다")
                .isEqualTo(target.amount());
    }

    @Test
    @DisplayName("요청 금액 집계는 createdAt 기준이다 — 거절된 건은 refundedAt 이 없다")
    void sumRequested_usesCreatedAt() {
        refundScenarioSeeder.refundRequested(newBuyerEmail());
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now().plusDays(1);

        long byCreatedAt = paymentRefundRepository
                .sumRefundAmountByRefundStatusAndCreatedAtBetween(RefundStatus.REQUESTED, from, to);
        long byRefundedAt = paymentRefundRepository
                .sumRefundAmountByRefundStatusAndRefundedAtBetween(RefundStatus.REQUESTED, from, to);

        assertThat(byCreatedAt).isPositive();
        assertThat(byRefundedAt)
                .as("REQUESTED 는 refundedAt 이 없으므로 이 기준으로는 항상 0 이다")
                .isZero();
    }

    private String newBuyerEmail() {
        return "refund-aggregation-" + System.nanoTime() + "@test.com";
    }
}
