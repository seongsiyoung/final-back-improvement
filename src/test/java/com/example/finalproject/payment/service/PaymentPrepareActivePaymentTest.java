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
    void prepare_whenUserHasUnresolvedPayment_throwsInProgress(PaymentStatus activeStatus) {
        CheckoutFixture fixture = prepareCheckout("active-" + activeStatus + "-" + System.nanoTime());
        makeActive(fixture, activeStatus);

        assertThatThrownBy(() -> paymentService.prepare(fixture.email(), fixture.request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().getCode())
                        .isEqualTo("PAYMENT-009"));

        assertThat(statusOf(fixture.response())).isEqualTo(activeStatus);
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
    void prepare_whenAnotherUserHasUnresolvedPayment_createsNewPayment() {
        CheckoutFixture blockedUser = prepareCheckout("blocked-user-" + System.nanoTime());
        makeActive(blockedUser, PaymentStatus.PENDING);
        CheckoutFixture otherUser = prepareCheckout("other-user-" + System.nanoTime());

        assertThat(statusOf(otherUser.response())).isEqualTo(PaymentStatus.READY);
        assertThat(statusOf(blockedUser.response())).isEqualTo(PaymentStatus.PENDING);
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
    void prepare_whenHistoricalReadyAndUnresolvedPaymentsExist_blocksWithoutReplacingReadyPayment() {
        CheckoutFixture pending = prepareCheckout("mixed-active-" + System.nanoTime());
        makeActive(pending, PaymentStatus.PENDING);
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.FAILED.name(), pending.response().getPaymentId());
        PostPaymentPrepareResponse ready = paymentService.prepare(pending.email(), pending.request());
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.PENDING.name(), pending.response().getPaymentId());

        assertThatThrownBy(() -> paymentService.prepare(pending.email(), pending.request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode().getCode())
                        .isEqualTo("PAYMENT-009"));

        assertThat(statusOf(pending.response())).isEqualTo(PaymentStatus.PENDING);
        assertThat(statusOf(ready)).isEqualTo(PaymentStatus.READY);
    }

    private CheckoutFixture prepareCheckout(String prefix) {
        String email = prefix + "@test.com";
        User user = seeder.seedUserWithAddress(email, "buyer1234!");
        Store store = seeder.seedStoreWithProducts(prefix + "-owner@test.com", 1, 10);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, org.springframework.data.domain.Pageable.unpaged())
                .getContent().get(0);
        PostPaymentPrepareRequest request = prepareRequest(product.getId());
        PostPaymentPrepareResponse response = paymentService.prepare(email, request);
        return new CheckoutFixture(email, user.getId(), request, response);
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

    private record CheckoutFixture(String email, Long userId, PostPaymentPrepareRequest request,
                                   PostPaymentPrepareResponse response) {
    }
}
