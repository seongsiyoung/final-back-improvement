package com.example.finalproject.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.TossStub;
import com.example.finalproject.user.domain.User;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 이 테스트는 일부러 클래스/메서드 레벨 {@code @Transactional}을 쓰지 않는다. 스케줄러가
 * 결제 건마다 독립된 물리 트랜잭션(completeConfirm/failPending 각각의 @Transactional)으로
 * 커밋된다는 게 이 기능의 핵심이라, 테스트 전체를 하나의 트랜잭션으로 감싸면 한 건의 실패가
 * (REQUIRED 전파로 같은 트랜잭션에 합류하면서) 나머지 건까지 rollback-only로 오염시켜
 * "한 건 실패가 나머지를 막지 않는다"를 실제로 증명하지 못한다. 그래서 픽스처 저장도
 * Spring Data 리포지토리의 save()(그 자체로 독립 트랜잭션)를 쓰고, updated_at 백데이트만
 * TransactionTemplate으로 별도 트랜잭션을 만들어 처리한다.
 */
class PendingPaymentReconciliationSchedulerTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private PendingPaymentReconciliationScheduler scheduler;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderLineRepository orderLineRepository;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private User user;
    private Product product;

    @BeforeEach
    void setUp() {
        user = seeder.seedUserWithAddress("scheduler-" + System.nanoTime() + "@test.com", "password1234!");
        Store store = seeder.seedStoreWithProducts(1, 100);
        product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0);
    }

    /**
     * 서버가 죽어 completeConfirm()이 아예 실행되지 못한 상황을 재현한다 — PaymentService/
     * PaymentConfirmCommandService의 어떤 보상 로직도 거치지 않고 저장소에 PENDING 결제를
     * 주문 라인(OrderLine)까지 갖춘 채로 직접 심는다. quantity가 completeConfirm()의 재고
     * 차감·StoreOrder 생성 로직을 실제로 태우는 데 필요하다.
     */
    private Payment seedStuckPayment(int quantity) {
        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-STUCK-" + System.nanoTime())
                .user(user)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(product.getEffectivePrice() * quantity)
                .totalDeliveryFee(0)
                .finalPrice(product.getEffectivePrice() * quantity)
                .deliveryAddress("서울시 강남구 테헤란로 1")
                .orderedAt(LocalDateTime.now())
                .build());

        orderLineRepository.save(OrderLine.builder()
                .order(order)
                .productId(product.getId())
                .storeId(product.getStore().getId())
                .priceSnapshot(product.getEffectivePrice())
                .productNameSnapshot(product.getProductName())
                .quantity(quantity)
                .build());

        return paymentRepository.save(Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(product.getEffectivePrice() * quantity)
                .paymentStatus(PaymentStatus.PENDING)
                .pgProvider("tosspayments")
                .pgOrderId("stuck-order-" + System.nanoTime())
                .build());
    }

    private void backdateUpdatedAt(Long paymentId, LocalDateTime timestamp) {
        transactionTemplate.executeWithoutResult(status -> entityManager
                .createNativeQuery("UPDATE payments SET updated_at = :ts WHERE id = :id")
                .setParameter("ts", timestamp)
                .setParameter("id", paymentId)
                .executeUpdate());
        entityManager.clear();
    }

    @Test
    void reconcileStalePendingPayments_whenPgApproved_completesStuckPaymentAndCreatesStoreOrder() {
        Payment payment = seedStuckPayment(2);
        backdateUpdatedAt(payment.getId(), LocalDateTime.now().minusMinutes(10));
        toss.stubGetPaymentByOrderIdStatus(payment.getPgOrderId(), "DONE");
        int stockBefore = productRepository.findById(product.getId()).orElseThrow().getStock();

        // 재현: 배치가 돌기 전에는 복구 수단이 없다 — 여기서 상태가 그대로 PENDING임을 먼저 확인
        Payment before = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(before.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        scheduler.reconcileStalePendingPayments();
        entityManager.clear();

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);

        // completeConfirm()이 실제로 재고를 차감하고 StoreOrder를 만들었는지까지 확인한다 —
        // 4단계가 복구하려는 대상이 결제 상태값 자체가 아니라 이 반영 로직이기 때문이다.
        int stockAfter = productRepository.findById(product.getId()).orElseThrow().getStock();
        assertThat(stockAfter).isEqualTo(stockBefore - 2);

        List<StoreOrder> storeOrders = storeOrderRepository.findAllByOrderId(payment.getOrder().getId());
        assertThat(storeOrders).hasSize(1);
    }

    @Test
    void reconcileStalePendingPayments_ignoresRecentPendingPayments() {
        Payment payment = seedStuckPayment(1);
        // updatedAt을 되돌리지 않음 — 방금 생성된 정상 진행 중 결제로 취급돼야 한다
        toss.stubGetPaymentByOrderIdStatus(payment.getPgOrderId(), "DONE");

        scheduler.reconcileStalePendingPayments();
        entityManager.clear();

        Payment after = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(after.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void reconcileStalePendingPayments_whenOnePaymentFails_stillProcessesTheOthers() {
        // A: 재고보다 훨씬 많은 수량을 주문해 completeConfirm() 안에서 INSUFFICIENT_STOCK으로 실패한다.
        Payment failingPayment = seedStuckPayment(product.getStock() + 1000);
        // B: 정상적으로 완결되어야 한다.
        Payment succeedingPayment = seedStuckPayment(1);
        backdateUpdatedAt(failingPayment.getId(), LocalDateTime.now().minusMinutes(10));
        backdateUpdatedAt(succeedingPayment.getId(), LocalDateTime.now().minusMinutes(10));
        toss.stubGetPaymentByOrderIdStatus(failingPayment.getPgOrderId(), "DONE");
        toss.stubGetPaymentByOrderIdStatus(succeedingPayment.getPgOrderId(), "DONE");

        // 두 결제 모두 대상인 한 번의 배치 실행 안에서, 실패한 건이 나머지를 막지 않아야 한다.
        scheduler.reconcileStalePendingPayments();
        entityManager.clear();

        Payment failedAfter = paymentRepository.findById(failingPayment.getId()).orElseThrow();
        assertThat(failedAfter.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);

        Payment succeededAfter = paymentRepository.findById(succeedingPayment.getId()).orElseThrow();
        assertThat(succeededAfter.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);
    }
}
