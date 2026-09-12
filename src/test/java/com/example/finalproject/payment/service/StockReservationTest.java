package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.dto.request.StockAdjustRequest;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.product.service.ProductService;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** prepare() 선점과 completeConfirm() 확정이 실재고를 정확히 한 번만 줄이는지 고정한다. */
class StockReservationTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentConfirmCommandService paymentConfirmCommandService;
    @Autowired private PaymentReconciliationCommandService paymentReconciliationCommandService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductService productService;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("결제 준비는 가용재고만 줄이고 실재고는 건드리지 않는다")
    void prepare_reservesWithoutTouchingStock() {
        Long productId = seedProduct(10);

        paymentService.prepare(newBuyer("reserve"), prepareRequest(productId, 3));

        assertThat(stockOf(productId)).as("승인 전에는 실재고가 줄면 안 된다").isEqualTo(10);
        assertThat(reservedOf(productId)).isEqualTo(3);
        assertThat(availableOf(productId)).isEqualTo(7);
    }

    @Test
    @DisplayName("승인이 끝나면 선점이 실재고 차감으로 확정된다")
    void completeConfirm_turnsReservationIntoStockDecrease() {
        Long productId = seedProduct(10);
        String email = newBuyer("confirm");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, prepareRequest(productId, 3));

        approve(email, prepared);

        assertThat(stockOf(productId)).isEqualTo(7);
        assertThat(reservedOf(productId)).as("확정된 선점은 남아 있으면 안 된다").isZero();
        assertThat(availableOf(productId)).isEqualTo(7);
    }

    @Test
    @DisplayName("선점과 확정을 거쳐도 재고는 한 번만 줄어든다")
    void reserveThenConfirm_decreasesStockOnlyOnce() {
        Long productId = seedProduct(10);
        String email = newBuyer("once");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, prepareRequest(productId, 4));
        assertThat(stockOf(productId)).isEqualTo(10);

        approve(email, prepared);

        assertThat(stockOf(productId))
                .as("선점 시점과 확정 시점에 각각 빠지면 2가 된다")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("가용재고보다 많이 담으면 결제 준비가 막힌다")
    void prepare_whenQuantityExceedsAvailableStock_throws() {
        Long productId = seedProduct(5);
        paymentService.prepare(newBuyer("hold"), prepareRequest(productId, 4));

        assertThatThrownBy(() -> paymentService.prepare(newBuyer("blocked"), prepareRequest(productId, 2)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        assertThat(reservedOf(productId)).as("막힌 요청의 선점이 남으면 안 된다").isEqualTo(4);
    }

    @Test
    @DisplayName("결제 준비가 중간에 실패하면 앞서 잡은 선점도 함께 롤백된다")
    void prepare_whenLaterProductFails_rollsBackEarlierReservation() {
        Long available = seedProduct(10);
        Long soldOut = seedProduct(0);

        assertThatThrownBy(() -> paymentService.prepare(
                newBuyer("rollback"), prepareRequest(Map.of(available, 2, soldOut, 1))))
                .isInstanceOf(BusinessException.class);

        assertThat(reservedOf(available))
                .as("같은 트랜잭션이므로 먼저 잡은 선점도 되돌아가야 한다")
                .isZero();
    }

    @Test
    @DisplayName("같은 상품을 동시에 선점해도 선점 합이 실재고를 넘지 않는다")
    void concurrentPrepare_neverReservesMoreThanStock() throws Exception {
        int stock = 3;
        int buyers = 6;
        Long productId = seedProduct(stock);

        // 재고보다 많은 구매자가 동시에 같은 상품을 담는다. 두 요청이 실제로 겹치는지는
        // 강제하지 않으며, 겹치지 않고 순차로 실행돼도 이 단언은 성립한다.
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            String email = newBuyer("race-" + i);
            attempts.add(() -> tryPrepare(email, prepareRequest(productId, 1)));
        }

        List<Boolean> results = runConcurrently(attempts);

        assertThat(results.stream().filter(Boolean::booleanValue).count())
                .as("재고를 넘겨 성공하면 안 된다")
                .isEqualTo(stock);
        assertThat(reservedOf(productId)).isEqualTo(stock);
        assertThat(availableOf(productId)).isZero();
    }

    @Test
    @DisplayName("장바구니 순서가 서로 반대인 두 요청이 동시에 와도 데드락이 나지 않는다")
    void concurrentPrepare_withReversedCartOrder_doesNotDeadlock() throws Exception {
        Long low = seedProduct(10);
        Long high = seedProduct(10);
        Long smaller = Math.min(low, high);
        Long larger = Math.max(low, high);
        String first = newBuyer("order-a");
        String second = newBuyer("order-b");

        // 요청이 담은 순서를 서로 반대로 고정한다. 구현이 productId 오름차순으로 잠그지
        // 않으면 두 트랜잭션이 상대의 락을 마주 기다려 PostgreSQL 이 한쪽을 데드락으로 중단시킨다.
        List<Boolean> results = runConcurrently(List.of(
                () -> tryPrepare(first, prepareRequest(orderedQuantities(smaller, larger))),
                () -> tryPrepare(second, prepareRequest(orderedQuantities(larger, smaller)))));

        assertThat(results).as("데드락이면 한쪽이 예외로 끝난다").containsExactly(true, true);
        assertThat(reservedOf(smaller)).isEqualTo(2);
        assertThat(reservedOf(larger)).isEqualTo(2);
    }

    private Map<Long, Integer> orderedQuantities(Long firstProductId, Long secondProductId) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        quantities.put(firstProductId, 1);
        quantities.put(secondProductId, 1);
        return quantities;
    }

    private List<Boolean> runConcurrently(List<Callable<Boolean>> actions) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(actions.size());
        try {
            List<Future<Boolean>> futures = actions.stream()
                    .map(action -> executor.submit(() -> awaitThen(start, action)))
                    .toList();
            start.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private Boolean awaitThen(CountDownLatch start, Callable<Boolean> action) throws Exception {
        start.await();
        return action.call();
    }

    /** 선점에 성공하면 true, 재고 부족이나 데드락 등으로 실패하면 false. */
    private boolean tryPrepare(String email, PostPaymentPrepareRequest request) {
        try {
            paymentService.prepare(email, request);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    @DisplayName("PENDING 결제를 실패로 종결하면 선점이 반납된다")
    void failPending_releasesReservation() {
        Long productId = seedProduct(10);
        String email = newBuyer("fail-pending");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, prepareRequest(productId, 3));
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), "key-" + System.nanoTime());

        paymentConfirmCommandService.failPending(prepared.getPaymentId());

        assertThat(reservedOf(productId)).isZero();
        assertThat(stockOf(productId)).as("실재고는 애초에 줄지 않았다").isEqualTo(10);
        assertThat(availableOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("REVERSAL_PENDING 결제를 실패로 종결하면 선점이 반납된다")
    void failReversalPending_releasesReservation() {
        Long productId = seedProduct(10);
        String email = newBuyer("fail-reversal");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, prepareRequest(productId, 2));
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), "key-" + System.nanoTime());
        paymentConfirmCommandService.markReversalPending(prepared.getPaymentId());

        paymentConfirmCommandService.failReversalPending(prepared.getPaymentId());

        assertThat(reservedOf(productId)).isZero();
        assertThat(availableOf(productId)).isEqualTo(10);
    }

    @Test
    @DisplayName("미확정 상태에서는 선점을 반납하지 않는다")
    void unresolvedStates_keepReservation() {
        Long productId = seedProduct(10);
        String email = newBuyer("unresolved");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, prepareRequest(productId, 2));
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), "key-" + System.nanoTime());

        assertThat(reservedOf(productId)).as("PENDING 은 승인이 성공했을 수 있다").isEqualTo(2);

        paymentConfirmCommandService.markReversalPending(prepared.getPaymentId());

        assertThat(reservedOf(productId)).as("REVERSAL_PENDING 은 취소가 아직 안 됐을 수 있다").isEqualTo(2);
    }

    @Test
    @DisplayName("관리자가 확인 필요 결제를 종결하면 선점이 반납된다")
    void adminResolve_releasesReservation() {
        Long productId = seedProduct(10);
        String email = newBuyer("admin-resolve");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, prepareRequest(productId, 4));
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), "key-" + System.nanoTime());
        paymentConfirmCommandService.markConfirmReconciliationRequired(prepared.getPaymentId());
        assertThat(reservedOf(productId)).isEqualTo(4);

        paymentReconciliationCommandService.resolvePayment(
                prepared.getPaymentId(), ReconciliationOutcome.NOT_CHARGED, null);

        assertThat(reservedOf(productId))
                .as("관리자 해제에서 빼먹으면 재고가 영원히 묶인다")
                .isZero();
    }

    @Test
    @DisplayName("새 결제 준비가 옛 READY 건을 대체하면 그 선점도 반납된다")
    void prepare_whenReplacingReadyPayment_releasesItsReservation() {
        Long productId = seedProduct(10);
        String email = newBuyer("replace-ready");
        paymentService.prepare(email, prepareRequest(productId, 3));
        assertThat(reservedOf(productId)).isEqualTo(3);

        paymentService.prepare(email, prepareRequest(productId, 2));

        assertThat(reservedOf(productId))
                .as("옛 건의 선점이 남으면 같은 사용자가 자기 재고를 두 번 잡는다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("승인으로 확정된 선점은 반납 대상이 아니다")
    void approvedPayment_isNotReleasedAgain() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("approved");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, prepareRequest(productId, 3));
        approve(buyer, prepared);
        // 같은 상품을 다른 사용자가 진행 중이다. 확정된 건을 또 반납하면 이 선점이 대신 깎인다.
        paymentService.prepare(newBuyer("bystander-fail"), prepareRequest(productId, 2));

        paymentConfirmCommandService.failPending(prepared.getPaymentId());

        assertThat(stockOf(productId)).isEqualTo(7);
        assertThat(reservedOf(productId))
                .as("진행 중인 다른 결제의 선점이 줄면 그 결제가 확정에서 터진다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("승인까지 간 결제를 관리자가 종결해도 다른 결제의 선점은 그대로다")
    void adminResolve_whenPaymentAlreadyConfirmed_keepsOtherReservations() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("confirmed-resolve");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, prepareRequest(productId, 3));
        approve(buyer, prepared);
        paymentService.prepare(newBuyer("bystander-admin"), prepareRequest(productId, 2));

        // 취소 거절 같은 경로는 승인 완료 결제도 확인 필요로 올린다.
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.RECONCILIATION_REQUIRED.name(), prepared.getPaymentId());

        paymentReconciliationCommandService.resolvePayment(
                prepared.getPaymentId(), ReconciliationOutcome.NOT_CHARGED, null);

        assertThat(reservedOf(productId))
                .as("이 결제의 선점은 이미 확정됐다. 반납하면 남의 선점을 깎는다")
                .isEqualTo(2);
        assertThat(stockOf(productId)).isEqualTo(7);
    }

    @Test
    @DisplayName("선점된 수량은 사장님 출고로 빠져나가지 않는다")
    void stockOut_cannotConsumeReservedQuantity() {
        Long productId = seedProduct(5);
        paymentService.prepare(newBuyer("reserve-all"), prepareRequest(productId, 5));

        assertThatThrownBy(() -> productService.stockOut(
                storeOwnerEmailOf(productId), productId, stockAdjustRequest(5)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INSUFFICIENT_STOCK));

        assertThat(stockOf(productId))
                .as("출고가 통과하면 확정 시점에 실재고가 음수가 된다")
                .isEqualTo(5);
    }

    private String storeOwnerEmailOf(Long productId) {
        return jdbcTemplate.queryForObject(
                "select u.email from products p join stores s on s.id = p.store_id "
                        + "join users u on u.id = s.owner_id where p.id = ?",
                String.class, productId);
    }

    private StockAdjustRequest stockAdjustRequest(int quantity) {
        StockAdjustRequest request = new StockAdjustRequest();
        ReflectionTestUtils.setField(request, "quantity", quantity);
        return request;
    }

    private void approve(String email, PostPaymentPrepareResponse prepared) {
        String paymentKey = "stock-reservation-key-" + System.nanoTime();
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), paymentKey);
        TossConfirmResponse pg = new TossConfirmResponse();
        ReflectionTestUtils.setField(pg, "paymentKey", paymentKey);
        ReflectionTestUtils.setField(pg, "totalAmount", prepared.getAmount());
        paymentConfirmCommandService.completeConfirm(prepared.getPaymentId(), paymentKey, pg);
    }

    private Long seedProduct(int stock) {
        Store store = seeder.seedStoreWithProducts(
                "stock-reservation-" + System.nanoTime() + "@test.com", 1, stock);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0);
        if (stock == 0) {
            // 재고 0이면 시더가 비활성으로 두지 않으므로 판매 상태는 그대로 두고 수량만 맞춘다.
            jdbcTemplate.update("update products set stock = 0 where id = ?", product.getId());
        }
        return product.getId();
    }

    private String newBuyer(String prefix) {
        String email = "stock-" + prefix + "-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "buyer1234!");
        return email;
    }

    private PostPaymentPrepareRequest prepareRequest(Long productId, int quantity) {
        return prepareRequest(Map.of(productId, quantity));
    }

    private PostPaymentPrepareRequest prepareRequest(Map<Long, Integer> quantities) {
        PostPaymentPrepareRequest request = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(request, "productQuantities", quantities);
        ReflectionTestUtils.setField(request, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(request, "deliveryAddress", "서울시 강남구 테헤란로 123");
        return request;
    }

    private int stockOf(Long productId) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private int reservedOf(Long productId) {
        return jdbcTemplate.queryForObject("select reserved from products where id = ?", Integer.class, productId);
    }

    private int availableOf(Long productId) {
        return stockOf(productId) - reservedOf(productId);
    }
}
