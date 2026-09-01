package com.example.finalproject.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.order.enums.StoreOrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StoreOrderRevertTest {

    private StoreOrder storeOrderWith(StoreOrderStatus status) {
        StoreOrder storeOrder = new StoreOrder();
        ReflectionTestUtils.setField(storeOrder, "status", status);
        return storeOrder;
    }

    @Test
    @DisplayName("CANCEL_REQUESTED 는 PENDING 으로 되돌아간다")
    void revertCancelRequest_goesToPending() {
        StoreOrder storeOrder = storeOrderWith(StoreOrderStatus.CANCEL_REQUESTED);

        storeOrder.revertCancelRequest();

        assertThat(storeOrder.getStatus()).isEqualTo(StoreOrderStatus.PENDING);
    }

    @Test
    @DisplayName("CANCEL_REQUESTED 가 아니면 되돌리지 않는다")
    void revertCancelRequest_fromDelivered_throws() {
        StoreOrder storeOrder = storeOrderWith(StoreOrderStatus.DELIVERED);

        assertThatThrownBy(storeOrder::revertCancelRequest)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("REFUND_REQUESTED 는 DELIVERED 로 되돌아간다")
    void revertRefundRequest_goesToDelivered() {
        StoreOrder storeOrder = storeOrderWith(StoreOrderStatus.REFUND_REQUESTED);

        storeOrder.revertRefundRequest();

        assertThat(storeOrder.getStatus()).isEqualTo(StoreOrderStatus.DELIVERED);
    }

    @Test
    @DisplayName("REFUND_REQUESTED 가 아닌 주문을 DELIVERED 로 만들지 않는다")
    void revertRefundRequest_fromPending_throws() {
        StoreOrder storeOrder = storeOrderWith(StoreOrderStatus.PENDING);

        assertThatThrownBy(storeOrder::revertRefundRequest)
                .isInstanceOf(BusinessException.class);
    }

}
