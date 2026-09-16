package com.example.finalproject.order.domain;

import com.example.finalproject.global.domain.BaseTimeEntity;
import com.example.finalproject.order.enums.OrderStatus;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.locationtech.jts.geom.Point;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 30)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_orders_user"))
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false)
    private OrderType orderType = OrderType.REGULAR;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "total_product_price", nullable = false)
    private Integer totalProductPrice;

    @Column(name = "total_delivery_fee", nullable = false)
    private Integer totalDeliveryFee;
    @Column(name = "final_price", nullable = false)
    private Integer finalPrice;

    @Column(name = "delivery_address", nullable = false, length = 255)
    private String deliveryAddress;

    @Column(name = "delivery_location", columnDefinition = "GEOGRAPHY(POINT,4326)")
    private Point deliveryLocation;

    @Column(name = "delivery_request", length = 255)
    private String deliveryRequest;

    @Column(name = "ordered_at", nullable = false)
    private LocalDateTime orderedAt;

    @OneToMany(mappedBy = "order")
    private List<StoreOrder> storeOrders = new ArrayList<>();


    @Builder
    public Order(String orderNumber, User user, OrderType orderType,
                 Integer totalProductPrice, Integer totalDeliveryFee,
                 Integer finalPrice, String deliveryAddress,
                 Point deliveryLocation, String deliveryRequest, LocalDateTime orderedAt) {

        this.orderNumber = orderNumber;
        this.user = user;
        this.orderType = orderType != null ? orderType : OrderType.REGULAR;
        this.totalProductPrice = totalProductPrice;
        this.totalDeliveryFee = totalDeliveryFee != null ? totalDeliveryFee : 0;
        this.finalPrice = finalPrice;
        this.deliveryAddress = deliveryAddress;
        this.deliveryLocation = deliveryLocation;
        this.deliveryRequest = deliveryRequest;
        this.orderedAt = orderedAt;
    }

    public void markPaid() {
        this.status = OrderStatus.PAID;
    }

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    /** 미결제 주문만 취소한다. */
    public void cancelUnpaid() {
        if (this.status != OrderStatus.PENDING) {
            return;
        }
        this.status = OrderStatus.CANCELLED;
    }

    public void partialCancel() {
        this.status = OrderStatus.PARTIAL_CANCELLED;
    }

    public void recalculateStatus() {
        boolean allCancelled = storeOrders.stream().allMatch(StoreOrder::isRefunded);

        if (allCancelled) {
            cancel();
        } else {
            partialCancel();
        }
    }
}
