package com.example.finalproject.testsupport;

import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.domain.OrderProduct;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderProductRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundResponsibility;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.payment.service.PaymentCommandService;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.user.domain.User;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final OrderProductRepository orderProductRepository;
    private final ProductRepository productRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PaymentCommandService paymentCommandService;

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

    /** 과거 시각에 멈춘 재조정 대상 결제. updatedAt은 JPA auditing을 우회해 설정한다. */
    public Long stuckPayment(String buyerEmail, PaymentStatus status, int minutesAgo) {
        RefundTarget target = approvedWithPendingStoreOrder(buyerEmail);
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();

        jdbcTemplate.update("update payments set updated_at = ?, payment_status = ? where id = ?",
                LocalDateTime.now().minusMinutes(minutesAgo), status.name(), payment.getId());
        return payment.getId();
    }

    public RefundTarget cancelRequested(String buyerEmail) {
        StoreOrder storeOrder = createApprovedPaymentWithStoreOrder(buyerEmail);
        storeOrder.requestCancel();
        storeOrderRepository.save(storeOrder);
        return targetOf(storeOrder, "고객 변심");
    }

    /** PG 취소 요청 뒤 결과 확인 없이 멈춘 환불. */
    public RefundTarget stuckInPgPending(String buyerEmail) {
        return stuckRefund(buyerEmail, RefundStatus.PG_PENDING);
    }

    /** PG 취소는 확인됐지만 로컬 장부 반영 전 멈춘 환불. */
    public RefundTarget stuckInPgApproved(String buyerEmail) {
        return stuckRefund(buyerEmail, RefundStatus.PG_APPROVED);
    }

    public RefundTarget refundStuckInReconciliationRequired(String buyerEmail) {
        return markRefundReconciliationRequired(refundRequested(buyerEmail));
    }

    public RefundTarget refundStuckInReconciliationRequired(String buyerEmail, StoreOrderStatus requestedStatus) {
        RefundTarget target = switch (requestedStatus) {
            case CANCEL_REQUESTED -> cancelRequested(buyerEmail);
            case REJECT_REQUESTED -> rejectRequested(buyerEmail);
            case REFUND_REQUESTED -> refundRequested(buyerEmail);
            default -> throw new IllegalArgumentException("unsupported requested status: " + requestedStatus);
        };
        return markRefundReconciliationRequired(target);
    }

    private RefundTarget markRefundReconciliationRequired(RefundTarget target) {
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();
        paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId())
                .orElseGet(() -> paymentRefundRepository.save(PaymentRefund.builder()
                        .payment(payment)
                        .storeOrder(storeOrderRepository.findById(target.storeOrderId()).orElseThrow())
                        .refundAmount(target.amount())
                        .refundReason(target.reason())
                        .refundStatus(RefundStatus.PG_PENDING)
                        .build()));
        paymentCommandService.markRefundReconciliationRequired(target);
        return target;
    }

    private RefundTarget stuckRefund(String buyerEmail, RefundStatus status) {
        RefundTarget target = cancelRequested(buyerEmail);
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.REFUND_REQUESTED.name(), payment.getId());
        PaymentRefund refund = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrderRepository.findById(target.storeOrderId()).orElseThrow())
                .refundAmount(target.amount())
                .refundReason(target.reason())
                .refundStatus(RefundStatus.PG_PENDING)
                .build();
        if (status == RefundStatus.PG_APPROVED) {
            refund.markPgApproved();
        }
        paymentRefundRepository.save(refund);
        return target;
    }

    /** 환불은 확정됐는데 취소 후속 처리가 남고 재고도 복구되지 않은 상태. */
    public RefundTarget refundCompletionLost(String buyerEmail) {
        return settleAsLostFollowUp(cancelRequested(buyerEmail));
    }

    /**
     * 결제는 환불로 끝났지만 이 매장 주문에는 환불 이력이 없는 상태.
     * 같은 주문의 다른 매장만 환불됐을 때 실제로 생기는 조합이다.
     */
    public RefundTarget refundCompletionLostWithoutRefundHistory(String buyerEmail) {
        RefundTarget target = cancelRequested(buyerEmail);
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();

        jdbcTemplate.update("update payments set payment_status = ?, refunded_amount = ? where id = ?",
                PaymentStatus.PARTIAL_REFUNDED.name(), payment.getAmount() / 2, payment.getId());
        jdbcTemplate.update("update store_orders set updated_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(30), target.storeOrderId());
        return target;
    }

    /** 같은 유실 상태를 거절 경로에서 만든다. */
    public RefundTarget rejectCompletionLost(String buyerEmail) {
        return settleAsLostFollowUp(rejectRequested(buyerEmail));
    }

    /** 유실 복구 대상에 사유가 다른 옛 환불 이력 한 건을 더 깔아 둔다. */
    public void addOlderRejectedRefund(RefundTarget target, String olderReason) {
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();
        PaymentRefund older = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrderRepository.findById(target.storeOrderId()).orElseThrow())
                .refundAmount(target.amount())
                .refundReason(olderReason)
                .refundStatus(RefundStatus.PG_PENDING)
                .responsibility(RefundResponsibility.PLATFORM)
                .isSettled(false)
                .build();
        older.markPgRejected();
        paymentRefundRepository.save(older);

        jdbcTemplate.update("update payment_refunds set created_at = ? where id = ?",
                LocalDateTime.now().minusDays(1), older.getId());
    }

    /**
     * applyRefund 는 커밋됐는데 AFTER_COMMIT 후속 처리가 실패한 상태를 만든다.
     * store_orders.updated_at 도 과거로 밀어 재조정 스캔의 시간 경계를 통과하게 한다.
     */
    private RefundTarget settleAsLostFollowUp(RefundTarget target) {
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();

        PaymentRefund refund = PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrderRepository.findById(target.storeOrderId()).orElseThrow())
                .refundAmount(target.amount())
                .refundReason(target.reason())
                .refundStatus(RefundStatus.PG_PENDING)
                .responsibility(RefundResponsibility.PLATFORM)
                .isSettled(false)
                .build();
        refund.markPgApproved();
        refund.adminApprove(target.amount());
        paymentRefundRepository.save(refund);

        jdbcTemplate.update("update payments set payment_status = ?, refunded_amount = ? where id = ?",
                PaymentStatus.REFUNDED.name(), payment.getAmount(), payment.getId());
        jdbcTemplate.update("update store_orders set updated_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(30), target.storeOrderId());
        return target;
    }

    /**
     * 통합 테스트는 DB 를 공유한다. 다른 테스트가 남긴 행이 스캔에 섞이지 않도록
     * 모든 후보의 updatedAt 을 현재로 밀어 시간 경계 밖으로 보낸다.
     */
    public void hideAllReconciliationTargets() {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("update payment_refunds set updated_at = ?", now);
        jdbcTemplate.update("update store_orders set updated_at = ?", now);
        jdbcTemplate.update("update payments set updated_at = ?", now);
    }

    /** 이 매장 주문에 걸린 행만 스캔의 시간 경계를 통과하게 만든다. */
    public void exposeReconciliationTarget(RefundTarget target) {
        exposeReconciliationTarget(target, 30);
    }

    /** minutesAgo 로 스캔 순서(updatedAt ASC)까지 지정한다. */
    public void exposeReconciliationTarget(RefundTarget target, int minutesAgo) {
        LocalDateTime past = LocalDateTime.now().minusMinutes(minutesAgo);
        jdbcTemplate.update("update store_orders set updated_at = ? where id = ?",
                past, target.storeOrderId());
        jdbcTemplate.update("update payment_refunds set updated_at = ? where store_order_id = ?",
                past, target.storeOrderId());
    }

    /** 이 매장 주문에 걸린 결제의 pgOrderId. Toss 조회 스텁을 건별로 나눌 때 쓴다. */
    public String pgOrderIdOf(RefundTarget target) {
        return paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPgOrderId();
    }

    /** 장부 규칙 위반을 만든다. 결제는 REFUND_REQUESTED 인데 이미 전액 환불된 것으로 기록돼 있다. */
    public void forceFullyRefundedAmount(RefundTarget target) {
        Payment payment = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow();
        jdbcTemplate.update("update payments set refunded_amount = ? where id = ?",
                payment.getAmount(), payment.getId());
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

    /**
     * 한 주문에 매장이 둘인 상태. Payment 는 주문 단위이고 StoreOrder 는 매장 단위라는
     * 구조를 그대로 만든다. 재조정 스캔이 결제 상태로 대상을 고를 때 이 차이가 드러난다.
     */
    public MultiStoreScenario twoStoresOneOrder(String buyerEmail) {
        Store storeA = loadTestDataSeeder.seedStoreWithProducts("multi-store-a@test.com", 1, 5);
        Store storeB = loadTestDataSeeder.seedStoreWithProducts("multi-store-b@test.com", 1, 5);
        User buyer = loadTestDataSeeder.seedUserWithAddress(buyerEmail, "buyer1234!");

        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-MS-" + System.nanoTime())
                .user(buyer)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(4000)
                .totalDeliveryFee(2000)
                .finalPrice(6000)
                .deliveryAddress("서울시 강남구 테헤란로 123")
                .deliveryLocation(GeometryUtil.createPoint(127.0276, 37.4979))
                .orderedAt(LocalDateTime.now())
                .build());

        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(6000)
                .paymentStatus(PaymentStatus.PENDING)
                .pgOrderId("PG-MULTI-STORE-" + System.nanoTime())
                .pgProvider("tosspayments")
                .build());
        payment.approve("multi-store-payment-key-" + System.nanoTime(), "pg-tx", null);
        paymentRepository.save(payment);

        return new MultiStoreScenario(
                order.getId(),
                storeOrderOf(order, storeA),
                storeOrderOf(order, storeB));
    }

    private RefundTarget storeOrderOf(Order order, Store store) {
        StoreOrder storeOrder = storeOrderRepository.save(StoreOrder.builder()
                .order(order)
                .store(store)
                .orderType(OrderType.REGULAR)
                .storeProductPrice(2000)
                .deliveryFee(1000)
                .finalPrice(3000)
                .build());
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().stream()
                .filter(candidate -> candidate.getProductName().equals("부하테스트상품-0"))
                .findFirst()
                .orElseThrow();
        orderProductRepository.save(OrderProduct.builder()
                .storeOrder(storeOrder)
                .product(product)
                .productNameSnapshot(product.getProductName())
                .priceSnapshot(product.getEffectivePrice())
                .quantity(1)
                .build());
        return targetOf(storeOrder, "고객 변심");
    }

    /** 매장 주문마다 취소를 요청한 상태로 만든다. 결제는 건드리지 않는다. */
    @Transactional
    public void requestCancelOn(RefundTarget target) {
        storeOrderRepository.findById(target.storeOrderId()).orElseThrow().requestCancel();
    }

    public record MultiStoreScenario(Long orderId, RefundTarget storeA, RefundTarget storeB) {
    }

    private StoreOrder createApprovedPaymentWithStoreOrder(String buyerEmail) {
        Store store = loadTestDataSeeder.seedStoreWithProducts(1, 1);
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
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().stream()
                .filter(candidate -> candidate.getProductName().equals("부하테스트상품-0"))
                .findFirst()
                .orElseThrow();
        orderProductRepository.save(OrderProduct.builder()
                .storeOrder(storeOrder)
                .product(product)
                .productNameSnapshot(product.getProductName())
                .priceSnapshot(product.getEffectivePrice())
                .quantity(1)
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
