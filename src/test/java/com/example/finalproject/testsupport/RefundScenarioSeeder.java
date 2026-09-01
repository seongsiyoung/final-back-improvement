package com.example.finalproject.testsupport;

import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.user.domain.User;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.util.ReflectionTestUtils;

@Component
@RequiredArgsConstructor
public class RefundScenarioSeeder {

    private final LoadTestDataSeeder loadTestDataSeeder;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final StoreOrderRepository storeOrderRepository;
    private final PaymentRefundRepository paymentRefundRepository;
    private final OrderLineRepository orderLineRepository;
    private final ProductRepository productRepository;

    public ConfirmScenario readyPayment(String buyerEmail) {
        return confirmScenario(buyerEmail, 10);
    }

    public ConfirmScenario outOfStockPayment(String buyerEmail) {
        return confirmScenario(buyerEmail, 0);
    }

    private ConfirmScenario confirmScenario(String buyerEmail, int stock) {
        Store store = loadTestDataSeeder.seedStoreWithProducts(1, stock);
        User buyer = loadTestDataSeeder.seedUserWithAddress(buyerEmail, "buyer1234!");
        Product product = productRepository.findAll().stream()
                .filter(candidate -> candidate.getStore().getId().equals(store.getId()))
                .findFirst()
                .orElseThrow();
        if (stock == 0) {
            ReflectionTestUtils.setField(product, "stock", 0);
            productRepository.save(product);
        }
        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-CS-" + System.nanoTime())
                .user(buyer)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(product.getEffectivePrice())
                .totalDeliveryFee(0)
                .finalPrice(product.getEffectivePrice())
                .deliveryAddress("서울시 강남구 테헤란로 123")
                .deliveryLocation(GeometryUtil.createPoint(127.0276, 37.4979))
                .orderedAt(LocalDateTime.now())
                .build());
        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(order.getFinalPrice())
                .paymentStatus(PaymentStatus.READY)
                .pgOrderId("PG-CS-" + System.nanoTime())
                .pgProvider("tosspayments")
                .build());
        orderLineRepository.save(OrderLine.builder()
                .order(order)
                .productId(product.getId())
                .storeId(store.getId())
                .priceSnapshot(product.getEffectivePrice())
                .productNameSnapshot(product.getProductName())
                .quantity(1)
                .build());

        PostPaymentConfirmRequest request = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentId", payment.getId());
        ReflectionTestUtils.setField(request, "paymentKey", "confirm-key-" + System.nanoTime());
        return new ConfirmScenario(buyerEmail, payment.getId(), request);
    }

    public RefundTarget approvedWithPendingStoreOrder(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        return targetOf(storeOrder, "고객 변심");
    }

    public RefundTarget cancelRequested(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        storeOrder.requestCancel();
        storeOrderRepository.save(storeOrder);
        return targetOf(storeOrder, "고객 변심");
    }

    public RefundTarget refundRequested(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        ReflectionTestUtils.setField(storeOrder, "deliveredAt", LocalDateTime.now());
        ReflectionTestUtils.setField(storeOrder, "status", StoreOrderStatus.DELIVERED);
        storeOrder.requestRefund("고객 변심");
        storeOrderRepository.save(storeOrder);

        Payment payment = paymentRepository.findByOrder_Id(storeOrder.getOrder().getId()).orElseThrow();
        paymentRefundRepository.save(PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(storeOrder.getFinalPrice())
                .refundReason("고객 변심")
                .refundStatus(RefundStatus.REQUESTED)
                .build());
        return targetOf(storeOrder, "고객 변심");
    }

    public RefundTarget deliveredStoreOrder(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        ReflectionTestUtils.setField(storeOrder, "deliveredAt", LocalDateTime.now());
        ReflectionTestUtils.setField(storeOrder, "status", StoreOrderStatus.DELIVERED);
        storeOrderRepository.save(storeOrder);
        return targetOf(storeOrder, "고객 변심");
    }

    public RefundTarget rejectRequested(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        storeOrder.requestReject();
        storeOrderRepository.save(storeOrder);
        return targetOf(storeOrder, "자동 거절 (미응답)");
    }

    /** 종결된 환불 이력(PG_REJECTED) 한 건만 있고 활성 건은 없는 주문. */
    public RefundTarget withClosedRefundHistory(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        Payment payment = paymentRepository.findByOrder_Id(storeOrder.getOrder().getId()).orElseThrow();
        PaymentRefund closed = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(storeOrder.getFinalPrice())
                .refundReason("고객 변심")
                .refundStatus(RefundStatus.PG_PENDING)
                .build();
        closed.markPgRejected();
        paymentRefundRepository.save(closed);
        return targetOf(storeOrder, "고객 변심");
    }

    /** 되살아난 PENDING 주문에 취소를 다시 요청한다. */
    @Transactional
    public RefundTarget requestCancelAgain(RefundTarget previous) {
        StoreOrder storeOrder = storeOrderRepository.findById(previous.storeOrderId()).orElseThrow();
        storeOrder.requestCancel();
        return new RefundTarget(
                previous.orderId(),
                previous.storeOrderId(),
                previous.amount(),
                previous.reason());
    }

    /**
     * 같은 주문에 승인된 환불 한 건과 거절된 환불 한 건.
     * 반환 RefundTarget.amount() 에는 승인된 금액만 담는다.
     */
    public RefundTarget approvedAndRejectedRefundHistory(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        Payment payment = paymentRepository.findByOrder_Id(storeOrder.getOrder().getId()).orElseThrow();

        PaymentRefund approved = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(storeOrder.getFinalPrice())
                .refundReason("승인된 환불")
                .refundStatus(RefundStatus.PG_PENDING)
                .build();
        approved.markPgApproved();
        approved.adminApprove(storeOrder.getFinalPrice());
        paymentRefundRepository.save(approved);

        PaymentRefund rejected = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(1500)
                .refundReason("거절된 환불")
                .refundStatus(RefundStatus.REQUESTED)
                .build();
        rejected.adminReject();
        paymentRefundRepository.save(rejected);

        return targetOf(storeOrder, "승인된 환불");
    }

    private StoreOrder createApprovedPaymentWithStoreOrder(String buyerEmail) {
        Store store = loadTestDataSeeder.seedStoreWithProducts(0, 1);
        User buyer = loadTestDataSeeder.seedUserWithAddress(buyerEmail, "buyer1234!");
        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-RS-" + System.nanoTime())
                .user(buyer)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(2000)
                .totalDeliveryFee(1000)
                .finalPrice(3000)
                .deliveryAddress("서울시 강남구 테헤란로 123")
                .deliveryLocation(GeometryUtil.createPoint(127.0276, 37.4979))
                .orderedAt(LocalDateTime.now())
                .build());
        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(3000)
                .paymentStatus(PaymentStatus.PENDING)
                .pgOrderId("PG-REFUND-SCENARIO-" + System.nanoTime())
                .pgProvider("tosspayments")
                .build());
        payment.approve("refund-scenario-payment-key-" + System.nanoTime(), "pg-tx", null);
        paymentRepository.save(payment);

        StoreOrder storeOrder = storeOrderRepository.save(StoreOrder.builder()
                .order(order)
                .store(store)
                .orderType(OrderType.REGULAR)
                .storeProductPrice(2000)
                .deliveryFee(1000)
                .finalPrice(3000)
                .build());
        return storeOrder;
    }

    private RefundTarget targetOf(StoreOrder storeOrder, String reason) {
        return new RefundTarget(
                storeOrder.getOrder().getId(),
                storeOrder.getId(),
                storeOrder.getFinalPrice(),
                reason);
    }

    public record ConfirmScenario(String email, Long paymentId, PostPaymentConfirmRequest request) {
    }
}
