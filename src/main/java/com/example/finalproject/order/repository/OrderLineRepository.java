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

    /** 승인되지 않은 미종결 결제의 상품별 선점 수량을 합산한다. */
    @Query("SELECT line.productId, SUM(line.quantity) FROM OrderLine line "
            + "JOIN Payment p ON p.order.id = line.order.id "
            + "WHERE p.paymentStatus IN (:statuses) "
            + "AND p.paidAt IS NULL "
            + "GROUP BY line.productId")
    List<Object[]> sumReservedQuantityByProduct(@Param("statuses") Collection<PaymentStatus> statuses);
}
