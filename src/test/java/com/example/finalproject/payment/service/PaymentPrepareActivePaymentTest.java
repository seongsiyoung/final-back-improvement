package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.user.domain.User;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentPrepareActivePaymentTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentConfirmCommandService paymentConfirmCommandService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"PENDING", "REVERSAL_PENDING", "RECONCILIATION_REQUIRED"})
    @DisplayName("멈춘 결제 한 건은 새 주문을 막지 않고 자기 선점만 붙잡는다")
    void prepare_whenUserHasOneUnresolvedPayment_createsNewPayment(PaymentStatus activeStatus) {
        CheckoutFixture fixture = prepareCheckout("active-" + activeStatus + "-" + System.nanoTime());
        makeActive(fixture, activeStatus);

        PostPaymentPrepareResponse next = paymentService.prepare(fixture.email(), fixture.request());

        assertThat(statusOf(next)).isEqualTo(PaymentStatus.READY);
        assertThat(statusOf(fixture.response()))
                .as("멈춘 결제는 새 준비가 건드리지 않는다")
                .isEqualTo(activeStatus);
        assertThat(reservedOf(fixture.productId()))
                .as("옛 선점이 반납되지 않은 채 새 선점이 더해진다")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("멈춘 결제가 상한에 닿으면 새 주문을 막는다")
    void prepare_whenUnresolvedPaymentsReachCap_throwsInProgress() {
        CheckoutFixture fixture = prepareCheckout("cap-" + System.nanoTime());
        makeActive(fixture, PaymentStatus.PENDING);
        for (int i = 0; i < 2; i++) {
            PostPaymentPrepareResponse extra =
                    paymentService.prepare(fixture.email(), fixture.request());
            paymentConfirmCommandService.startConfirm(
                    fixture.email(), extra.getPaymentId(), "payment-key-" + System.nanoTime());
        }

        assertThat(reservedOf(fixture.productId()))
                .as("상한까지는 통과하고 선점이 누적된다")
                .isEqualTo(3);

        assertThatThrownBy(() -> paymentService.prepare(fixture.email(), fixture.request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().getCode())
                        .isEqualTo("PAYMENT-009"));

        assertThat(reservedOf(fixture.productId()))
                .as("막힌 요청은 선점을 남기지 않는다")
                .isEqualTo(3);
    }

    @Test
    void prepare_whenUserHasReadyPayment_replacesItWithOneNewReadyPayment() {
        CheckoutFixture first = prepareCheckout("ready-replace-" + System.nanoTime());

        PostPaymentPrepareResponse replacement = paymentService.prepare(first.email(), first.request());

        assertThat(statusOf(first.response())).isEqualTo(PaymentStatus.FAILED);
        assertThat(statusOf(replacement)).isEqualTo(PaymentStatus.READY);
        assertThat(paymentRepository.countByOrder_UserIdAndPaymentStatusIn(
                first.userId(), List.of(PaymentStatus.READY, PaymentStatus.PENDING,
                        PaymentStatus.REVERSAL_PENDING, PaymentStatus.RECONCILIATION_REQUIRED)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("교체는 요청한 사용자의 결제만 건드린다")
    void prepare_doesNotTouchAnotherUsersReadyPayment() {
        CheckoutFixture other = prepareCheckout("other-user-" + System.nanoTime());
        CheckoutFixture mine = prepareCheckout("my-user-" + System.nanoTime());

        paymentService.prepare(mine.email(), mine.request());

        assertThat(statusOf(other.response()))
                .as("남의 READY 를 FAILED 로 닫으면 그 주문이 취소되고 선점도 풀린다")
                .isEqualTo(PaymentStatus.READY);
        assertThat(reservedOf(other.productId())).isEqualTo(1);
    }

    @Test
    void prepare_whenMultipleHistoricalReadyPaymentsExist_replacesAllOfThem() {
        CheckoutFixture first = prepareCheckout("multiple-ready-" + System.nanoTime());
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.FAILED.name(), first.response().getPaymentId());
        PostPaymentPrepareResponse second = paymentService.prepare(first.email(), first.request());
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.READY.name(), first.response().getPaymentId());

        PostPaymentPrepareResponse replacement = paymentService.prepare(first.email(), first.request());

        assertThat(statusOf(first.response())).isEqualTo(PaymentStatus.FAILED);
        assertThat(statusOf(second)).isEqualTo(PaymentStatus.FAILED);
        assertThat(statusOf(replacement)).isEqualTo(PaymentStatus.READY);
    }

    @Test
    @DisplayName("READY 와 멈춘 결제가 함께 있으면 READY 만 교체한다")
    void prepare_whenReadyAndUnresolvedPaymentsExist_replacesOnlyTheReadyPayment() {
        CheckoutFixture pending = prepareCheckout("mixed-active-" + System.nanoTime());
        makeActive(pending, PaymentStatus.PENDING);
        PostPaymentPrepareResponse ready = paymentService.prepare(pending.email(), pending.request());

        PostPaymentPrepareResponse replacement =
                paymentService.prepare(pending.email(), pending.request());

        assertThat(statusOf(pending.response()))
                .as("멈춘 결제는 교체 대상이 아니다")
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(statusOf(ready)).isEqualTo(PaymentStatus.FAILED);
        assertThat(statusOf(replacement)).isEqualTo(PaymentStatus.READY);
        assertThat(reservedOf(pending.productId()))
                .as("멈춘 결제 1 + 새 READY 1. 교체된 READY 의 선점은 반납됐다")
                .isEqualTo(2);
    }

    private CheckoutFixture prepareCheckout(String prefix) {
        String email = prefix + "@test.com";
        User user = seeder.seedUserWithAddress(email, "buyer1234!");
        Store store = seeder.seedStoreWithProducts(prefix + "-owner@test.com", 1, 10);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, org.springframework.data.domain.Pageable.unpaged())
                .getContent().get(0);
        PostPaymentPrepareRequest request = prepareRequest(product.getId());
        PostPaymentPrepareResponse response = paymentService.prepare(email, request);
        return new CheckoutFixture(email, user.getId(), product.getId(), request, response);
    }

    private PostPaymentPrepareRequest prepareRequest(Long productId) {
        PostPaymentPrepareRequest request = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(request, "productQuantities", Map.of(productId, 1));
        ReflectionTestUtils.setField(request, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(request, "deliveryAddress", "서울시 강남구 테헤란로 123");
        return request;
    }

    private void makeActive(CheckoutFixture fixture, PaymentStatus status) {
        paymentConfirmCommandService.startConfirm(
                fixture.email(), fixture.response().getPaymentId(), "payment-key-" + System.nanoTime());
        if (status == PaymentStatus.REVERSAL_PENDING) {
            paymentConfirmCommandService.markReversalPending(fixture.response().getPaymentId());
        } else if (status == PaymentStatus.RECONCILIATION_REQUIRED) {
            paymentConfirmCommandService.markConfirmReconciliationRequired(fixture.response().getPaymentId());
        }
    }

    private PaymentStatus statusOf(PostPaymentPrepareResponse response) {
        return paymentRepository.findById(response.getPaymentId()).orElseThrow().getPaymentStatus();
    }

    private int reservedOf(Long productId) {
        return productRepository.findById(productId).orElseThrow().getReserved();
    }

    private record CheckoutFixture(String email, Long userId, Long productId,
                                   PostPaymentPrepareRequest request,
                                   PostPaymentPrepareResponse response) {
    }
}
