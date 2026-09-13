package com.example.finalproject.payment.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.PaymentConfirmCommandService;
import com.example.finalproject.payment.service.PaymentService;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** 결제창을 열어두고 떠난 READY 결제가 선점을 되돌리는지 고정한다. */
class ReadyPaymentExpirationSchedulerTest extends IntegrationTestSupport {

    @Autowired private ReadyPaymentExpirationScheduler scheduler;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentConfirmCommandService paymentConfirmCommandService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("임계 시간이 지난 준비 결제는 선점을 되돌리고 실패로 종결된다")
    void expiredReadyPayment_isFailedAndReleasesReservation() {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);
        backdateCreatedAt(prepared.getPaymentId(), 60);

        scheduler.expireStaleReadyPayments();

        assertThat(statusOf(prepared)).isEqualTo(PaymentStatus.FAILED);
        assertThat(reservedOf(productId)).isZero();
        assertThat(stockOf(productId)).as("팔린 적이 없으므로 실재고는 그대로다").isEqualTo(10);
    }

    @Test
    @DisplayName("임계 시간 전이면 건드리지 않는다")
    void recentReadyPayment_isUntouched() {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);

        scheduler.expireStaleReadyPayments();

        assertThat(statusOf(prepared)).isEqualTo(PaymentStatus.READY);
        assertThat(reservedOf(productId)).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "REVERSAL_PENDING"})
    @DisplayName("미확정 결제는 이 스캔의 대상이 아니다")
    void unresolvedPayments_areNotExpired(PaymentStatus status) {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);
        paymentConfirmCommandService.startConfirm(
                buyerOf(prepared), prepared.getPaymentId(), "expire-key-" + System.nanoTime());
        if (status == PaymentStatus.REVERSAL_PENDING) {
            paymentConfirmCommandService.markReversalPending(prepared.getPaymentId());
        }
        backdateCreatedAt(prepared.getPaymentId(), 60);

        scheduler.expireStaleReadyPayments();

        assertThat(statusOf(prepared))
                .as("승인이 성공했을 수 있어 만료로 종결하면 안 된다")
                .isEqualTo(status);
        assertThat(reservedOf(productId)).isEqualTo(3);
    }

    @Test
    @DisplayName("결제가 먼저 시작되면 만료 처리는 아무것도 하지 않는다")
    void whenConfirmStartsFirst_expiryDoesNothing() {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);
        backdateCreatedAt(prepared.getPaymentId(), 60);
        paymentConfirmCommandService.startConfirm(
                buyerOf(prepared), prepared.getPaymentId(), "race-key-" + System.nanoTime());

        // 스캔이 대상 목록을 만든 뒤 고객이 결제를 시작한 상황과 같다.
        paymentConfirmCommandService.expireReadyPayment(prepared.getPaymentId());

        assertThat(statusOf(prepared))
                .as("돈은 받았는데 재고만 반납되는 상태를 만들면 안 된다")
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(reservedOf(productId)).isEqualTo(3);
    }

    @Test
    @DisplayName("결제가 시작돼 행을 쥐고 있으면 만료 처리가 그 뒤에 실행되고 아무것도 바꾸지 않는다")
    void whenConfirmHoldsTheRow_expiryWaitsAndDoesNothing() throws Exception {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);
        backdateCreatedAt(prepared.getPaymentId(), 60);

        CountDownLatch confirmHoldsLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 결제 시작을 커밋하지 않은 채 행 락을 잠시 쥐고 있게 한다. 그 사이에 만료가
            // 들어와야 락의 효과를 잴 수 있다 — 커밋이 먼저 끝나면 상태 재확인만으로 통과해
            // 락을 지워도 테스트가 녹색이 된다.
            Future<?> confirm = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                paymentConfirmCommandService.startConfirm(
                        buyerOf(prepared), prepared.getPaymentId(), "hold-key-" + System.nanoTime());
                confirmHoldsLock.countDown();
                sleepQuietly(2000);
            }));

            assertThat(confirmHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> expiry = executor.submit(
                    () -> paymentConfirmCommandService.expireReadyPayment(prepared.getPaymentId()));

            confirm.get(30, TimeUnit.SECONDS);
            expiry.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(statusOf(prepared))
                .as("락이 없으면 만료가 커밋 전 READY 를 읽고 PENDING 을 FAILED 로 덮는다")
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(reservedOf(productId))
                .as("돈은 받았는데 재고만 반납된 상태가 되면 안 된다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("한 건이 실패해도 나머지 만료 대상은 계속 처리된다")
    void whenOnePaymentFails_stillExpiresTheOthers() {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse failing = prepare(productId, 1);
        PostPaymentPrepareResponse succeeding = prepare(productId, 2);
        backdateCreatedAt(failing.getPaymentId(), 90);
        backdateCreatedAt(succeeding.getPaymentId(), 60);
        // 반납이 상품을 찾지 못해 실패한다. 뒤에 오는 건과 상태를 공유하지 않는 실패 장치다.
        jdbcTemplate.update("update order_lines set product_id = ? where order_id = ?",
                Long.MAX_VALUE, orderIdOf(failing));

        scheduler.expireStaleReadyPayments();

        assertThat(statusOf(failing)).isEqualTo(PaymentStatus.READY);
        assertThat(statusOf(succeeding)).isEqualTo(PaymentStatus.FAILED);
        assertThat(reservedOf(productId))
                .as("실패한 건의 선점 1만 남는다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("만료 대상은 오래된 것부터 상한만큼만 가져온다")
    void findExpiredReadyPayments_isOrderedAndLimited() {
        Long productId = seedProduct(10);
        Long oldest = prepare(productId, 1).getPaymentId();
        Long middle = prepare(productId, 1).getPaymentId();
        Long newest = prepare(productId, 1).getPaymentId();
        backdateCreatedAt(oldest, 90);
        backdateCreatedAt(middle, 60);
        backdateCreatedAt(newest, 40);

        List<Payment> limited = paymentRepository.findExpiredReadyPayments(
                PaymentStatus.READY, LocalDateTime.now().minusMinutes(30), PageRequest.of(0, 2));

        assertThat(limited).hasSize(2);
        assertThat(limited).extracting(Payment::getCreatedAt).isSorted();

        List<Long> seeded = List.of(oldest, middle, newest);
        List<Long> seededTargets = paymentRepository.findExpiredReadyPayments(
                        PaymentStatus.READY, LocalDateTime.now().minusMinutes(30), PageRequest.of(0, 100))
                .stream().map(Payment::getId).filter(seeded::contains).toList();

        assertThat(seededTargets).containsExactly(oldest, middle, newest);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Long orderIdOf(PostPaymentPrepareResponse prepared) {
        return paymentRepository.findById(prepared.getPaymentId()).orElseThrow().getOrder().getId();
    }

    private final java.util.Map<Long, String> buyerByPaymentId = new java.util.HashMap<>();

    private String buyerOf(PostPaymentPrepareResponse prepared) {
        return buyerByPaymentId.get(prepared.getPaymentId());
    }

    private PostPaymentPrepareResponse prepare(Long productId, int quantity) {
        String email = "ready-expiry-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "buyer1234!");
        PostPaymentPrepareRequest request = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(request, "productQuantities", Map.of(productId, quantity));
        ReflectionTestUtils.setField(request, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(request, "deliveryAddress", "서울시 강남구 테헤란로 123");
        PostPaymentPrepareResponse prepared = paymentService.prepare(email, request);
        buyerByPaymentId.put(prepared.getPaymentId(), email);
        return prepared;
    }

    private Long seedProduct(int stock) {
        Store store = seeder.seedStoreWithProducts(
                "ready-expiry-owner-" + System.nanoTime() + "@test.com", 1, stock);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0);
        return product.getId();
    }

    /** createdAt 은 JPA auditing 이 저장 시각으로 채우므로 JDBC 로 우회한다. */
    private void backdateCreatedAt(Long paymentId, int minutesAgo) {
        jdbcTemplate.update("update payments set created_at = now() - make_interval(mins => ?) where id = ?",
                minutesAgo, paymentId);
    }

    private PaymentStatus statusOf(PostPaymentPrepareResponse prepared) {
        return paymentRepository.findById(prepared.getPaymentId()).orElseThrow().getPaymentStatus();
    }

    private int stockOf(Long productId) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private int reservedOf(Long productId) {
        return jdbcTemplate.queryForObject("select reserved from products where id = ?", Integer.class, productId);
    }
}
