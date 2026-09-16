package com.example.finalproject.product.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.service.PaymentConfirmCommandService;
import com.example.finalproject.payment.service.PaymentService;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 선점 장부 불일치 탐지 결과를 검증한다. */
class StockReservationDriftCheckerTest extends IntegrationTestSupport {

    @Autowired private StockReservationDriftChecker driftChecker;
    @Autowired private PaymentService paymentService;
    @Autowired private PaymentConfirmCommandService paymentConfirmCommandService;
    @Autowired private ProductRepository productRepository;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String buyerEmail;

    @Test
    @DisplayName("전이표대로 움직이면 불일치가 나오지 않는다")
    void whenLedgerIsConsistent_reportsNoDrift() {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);

        assertThat(driftChecker.reportDrift()).doesNotContain(productId);

        paymentConfirmCommandService.startConfirm(
                buyerEmail, prepared.getPaymentId(), "drift-key-" + System.nanoTime());
        paymentConfirmCommandService.failPending(prepared.getPaymentId());

        assertThat(driftChecker.reportDrift()).doesNotContain(productId);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"READY", "PENDING", "REVERSAL_PENDING", "RECONCILIATION_REQUIRED"})
    @DisplayName("선점을 쥐고 있는 네 상태 모두 기댓값에 포함된다")
    void allHoldingStatuses_areCountedAsExpected(PaymentStatus holdingStatus) {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);
        forceStatus(prepared.getPaymentId(), holdingStatus);

        assertThat(driftChecker.reportDrift())
                .as("이 상태가 목록에서 빠지면 선점 3이 기댓값 0과 어긋난다")
                .doesNotContain(productId);
    }

    @Test
    @DisplayName("승인까지 간 결제가 확인 필요로 올라가도 불일치가 아니다")
    void confirmedPaymentInReconciliation_isNotDrift() {
        Long productId = seedProduct(10);
        String buyer = "drift-confirmed-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(buyer, "buyer1234!");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, prepareRequest(productId, 3));
        approve(buyer, prepared);
        forceStatus(prepared.getPaymentId(), PaymentStatus.RECONCILIATION_REQUIRED);

        assertThat(driftChecker.reportDrift())
                .as("승인이 끝난 선점은 이미 실재고 차감으로 확정됐다. 기댓값에 넣으면 거짓 불일치가 된다")
                .doesNotContain(productId);
    }

    @Test
    @DisplayName("선점이 실제보다 많으면 불일치로 잡는다")
    void whenReservedIsInflated_reportsDrift() {
        Long productId = seedProduct(10);
        prepare(productId, 3);
        jdbcTemplate.update("update products set reserved = 5 where id = ?", productId);

        assertThat(driftChecker.reportDrift()).contains(productId);
        assertThat(reservedOf(productId)).as("점검은 값을 고치지 않는다").isEqualTo(5);
    }

    @Test
    @DisplayName("결제는 살아 있는데 선점이 없으면 불일치로 잡는다")
    void whenReservationIsMissing_reportsDrift() {
        Long productId = seedProduct(10);
        prepare(productId, 3);
        jdbcTemplate.update("update products set reserved = 0 where id = ?", productId);

        assertThat(driftChecker.reportDrift()).contains(productId);
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("종결된 결제가 선점을 남겨도 불일치로 잡는다")
    void whenSettledPaymentStillHoldsReservation_reportsDrift() {
        Long productId = seedProduct(10);
        PostPaymentPrepareResponse prepared = prepare(productId, 3);
        forceStatus(prepared.getPaymentId(), PaymentStatus.FAILED);

        assertThat(driftChecker.reportDrift()).contains(productId);
        assertThat(reservedOf(productId)).as("기댓값 0, 실제 3이다").isEqualTo(3);
    }

    @Test
    @DisplayName("같은 상품을 여러 결제가 쥐고 있어도 합계로 대조한다")
    void multiplePayments_areSummedPerProduct() {
        Long productId = seedProduct(10);
        prepare(productId, 3);
        prepare(productId, 2);

        assertThat(reservedOf(productId)).isEqualTo(5);
        assertThat(driftChecker.reportDrift())
                .as("합계가 아니라 한 건만 세면 5와 3이 어긋난다")
                .doesNotContain(productId);
    }

    @Test
    @DisplayName("한 결제가 여러 상품을 쥐고 있어도 상품별로 대조한다")
    void multipleProducts_areGroupedPerProduct() {
        Long first = seedProduct(10);
        Long second = seedProduct(10);
        buyerEmail = "drift-multi-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(buyerEmail, "buyer1234!");
        paymentService.prepare(buyerEmail, prepareRequest(Map.of(first, 2, second, 4)));

        assertThat(reservedOf(first)).isEqualTo(2);
        assertThat(reservedOf(second)).isEqualTo(4);
        assertThat(driftChecker.reportDrift()).doesNotContain(first, second);
    }

    private void approve(String email, PostPaymentPrepareResponse prepared) {
        String paymentKey = "drift-approve-" + System.nanoTime();
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), paymentKey);
        var pg = new com.example.finalproject.payment.dto.response.TossConfirmResponse();
        ReflectionTestUtils.setField(pg, "paymentKey", paymentKey);
        ReflectionTestUtils.setField(pg, "totalAmount", prepared.getAmount());
        paymentConfirmCommandService.completeConfirm(prepared.getPaymentId(), paymentKey, pg);
    }

    private void forceStatus(Long paymentId, PaymentStatus status) {
        jdbcTemplate.update("update payments set payment_status = ? where id = ?", status.name(), paymentId);
    }

    private PostPaymentPrepareResponse prepare(Long productId, int quantity) {
        buyerEmail = "drift-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(buyerEmail, "buyer1234!");
        return paymentService.prepare(buyerEmail, prepareRequest(productId, quantity));
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

    private Long seedProduct(int stock) {
        Store store = seeder.seedStoreWithProducts(
                "drift-owner-" + System.nanoTime() + "@test.com", 1, stock);
        return productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();
    }

    private int reservedOf(Long productId) {
        return jdbcTemplate.queryForObject("select reserved from products where id = ?", Integer.class, productId);
    }
}
