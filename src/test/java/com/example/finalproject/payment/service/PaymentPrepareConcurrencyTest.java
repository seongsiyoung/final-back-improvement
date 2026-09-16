package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.finalproject.delivery.service.DeliveryFeeService;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentPrepareConcurrencyTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private PaymentConfirmCommandService paymentConfirmCommandService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockBean private DeliveryFeeService deliveryFeeService;

    @Test
    void prepare_concurrentlyForSameUser_leavesExactlyOneActivePayment() throws Exception {
        String email = "concurrent-prepare-" + System.nanoTime() + "@test.com";
        User user = seeder.seedUserWithAddress(email, "buyer1234!");
        Store store = seeder.seedStoreWithProducts("concurrent-owner-" + System.nanoTime() + "@test.com", 1, 10);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(
                store, org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);
        PostPaymentPrepareRequest request = prepareRequest(product.getId());

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch deliveryFeeEntered = new CountDownLatch(2);
        when(deliveryFeeService.calculateTotalDeliveryFee(eq(user.getId()), anyList())).thenAnswer(invocation -> {
            deliveryFeeEntered.countDown();
            deliveryFeeEntered.await(1, TimeUnit.SECONDS);
            return 3000;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> prepareAfterStart(start, email, request));
            Future<?> second = executor.submit(() -> prepareAfterStart(start, email, request));
            start.countDown();

            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(paymentRepository.countByOrder_UserIdAndPaymentStatusIn(user.getId(), List.of(
                PaymentStatus.READY,
                PaymentStatus.PENDING,
                PaymentStatus.REVERSAL_PENDING,
                PaymentStatus.RECONCILIATION_REQUIRED))).isEqualTo(1);
    }


    /**
     * 옛 READY 를 잠그기 전에 읽으면, 그 사이 PENDING 이 된 결제를 낡은 READY 로 보고 닫는다.
     * PG 로 이미 나간 결제가 FAILED 가 되고 선점과 주문까지 되돌아간다.
     */
    @Test
    @DisplayName("결제 시작과 겹쳐도 PG 로 간 결제를 실패로 닫지 않는다")
    void prepare_whileStartConfirmHoldsTheReadyPayment_leavesItPending() throws Exception {
        when(deliveryFeeService.calculateTotalDeliveryFee(anyLong(), anyList())).thenReturn(3000);
        String email = "stale-ready-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "buyer1234!");
        Store store = seeder.seedStoreWithProducts("stale-owner-" + System.nanoTime() + "@test.com", 1, 10);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(
                store, org.springframework.data.domain.Pageable.unpaged()).getContent().get(0);
        PostPaymentPrepareRequest request = prepareRequest(product.getId());
        PostPaymentPrepareResponse first = paymentService.prepare(email, request);

        CountDownLatch confirmLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> confirming = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                paymentConfirmCommandService.startConfirm(
                        email, first.getPaymentId(), "stale-key-" + System.nanoTime());
                confirmLocked.countDown();
                awaitQuietly(release);
            }));

            assertThat(confirmLocked.await(10, TimeUnit.SECONDS))
                    .as("startConfirm 이 잠금을 잡지 못했다")
                    .isTrue();
            Future<?> preparing = executor.submit(() -> paymentService.prepare(email, request));
            awaitRowLockWaiter(jdbcTemplate);
            release.countDown();

            confirming.get(10, TimeUnit.SECONDS);
            preparing.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(paymentRepository.findById(first.getPaymentId()).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PENDING);
        assertThat(productRepository.findById(product.getId()).orElseThrow().getReserved())
                .as("멈춘 결제의 선점 1 + 새 준비의 선점 1")
                .isEqualTo(2);
    }


    /** 홀더의 대기 예산은 awaitRowLockWaiter 의 데드라인보다 커야 먼저 커밋하지 않는다. */
    private void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금을 놓아줄 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void prepareAfterStart(CountDownLatch start, String email, PostPaymentPrepareRequest request) {
        try {
            start.await();
            paymentService.prepare(email, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private PostPaymentPrepareRequest prepareRequest(Long productId) {
        PostPaymentPrepareRequest request = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(request, "productQuantities", Map.of(productId, 1));
        ReflectionTestUtils.setField(request, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(request, "deliveryAddress", "서울시 강남구 테헤란로 123");
        return request;
    }
}
