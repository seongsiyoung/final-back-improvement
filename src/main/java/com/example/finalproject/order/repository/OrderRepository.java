package com.example.finalproject.order.repository;

import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.enums.OrderStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByIdAndUserId(Long orderId, Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndStatusIn(Long userId, Collection<OrderStatus> statuses);

}
