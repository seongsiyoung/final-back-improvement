package com.example.finalproject.order.repository;

import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.payment.enums.PaymentStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {
    List<OrderLine> findAllByOrderId(Long orderId);

    /**
     * 선점이 살아 있어야 하는 수량. 아직 종결되지 않은 결제의 주문 라인을 상품별로 합친다.
     * 이 값이 Product.reserved 의 기댓값이다.
     *
     * <p>paidAt 이 있는 결제는 제외한다. 승인이 끝난 결제는 completeConfirm() 이 선점을 실재고
     * 차감으로 확정했으므로 더 쥐고 있지 않다. 그런 결제도 RECONCILIATION_REQUIRED 로 올 수 있다
     * — 취소 거절(handleCancelRejection)이 승인 완료 주문의 결제를 그 상태로 올린다.
     * paidAt 은 approve() 에서만 채워지고 지워지지 않아 확정 여부의 판별자가 된다.
     */
    @Query("SELECT line.productId, SUM(line.quantity) FROM OrderLine line "
            + "JOIN Payment p ON p.order.id = line.order.id "
            + "WHERE p.paymentStatus IN (:statuses) "
            + "AND p.paidAt IS NULL "
            + "GROUP BY line.productId")
    List<Object[]> sumReservedQuantityByProduct(@Param("statuses") Collection<PaymentStatus> statuses);
}
