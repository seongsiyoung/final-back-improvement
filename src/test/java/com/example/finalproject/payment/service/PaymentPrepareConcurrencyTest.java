package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.example.finalproject.delivery.service.DeliveryFeeService;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentPrepareConcurrencyTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private LoadTestDataSeeder seeder;
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
