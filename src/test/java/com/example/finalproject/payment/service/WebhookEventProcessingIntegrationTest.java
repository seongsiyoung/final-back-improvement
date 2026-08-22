package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.WebhookEvent;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.WebhookEventStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.repository.WebhookEventRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.TossStub;
import com.example.finalproject.user.domain.User;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 웹훅 수신부터 실제 결제 반영까지 이어지는 비동기 배선(WebhookEventListener의
 * AFTER_COMMIT + @Async, WebhookEventProcessor)이 실제로 동작하는지 검증한다.
 * reconcile()의 상태 분기 로직 자체는 PaymentReconciliationServiceTest(Task 2)와
 * PendingPaymentReconciliationSchedulerTest(Task 3)가 이미 검증했으므로, 여기서는
 * "웹훅이 도착하면 비동기로 처리까지 이어지는가"만 확인한다.
 */
class WebhookEventProcessingIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private WebhookEventRepository webhookEventRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderLineRepository orderLineRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private LoadTestDataSeeder seeder;

    private String pgOrderId;

    @BeforeEach
    void setUp() {
        User user = seeder.seedUserWithAddress("webhook-" + System.nanoTime() + "@test.com", "password1234!");
        Store store = seeder.seedStoreWithProducts(1, 100);
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0);

        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-WEBHOOK-" + System.nanoTime())
                .user(user)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(product.getEffectivePrice())
                .totalDeliveryFee(0)
                .finalPrice(product.getEffectivePrice())
                .deliveryAddress("서울시 강남구 테헤란로 1")
                .orderedAt(LocalDateTime.now())
                .build());

        orderLineRepository.save(OrderLine.builder()
                .order(order)
                .productId(product.getId())
                .storeId(product.getStore().getId())
                .priceSnapshot(product.getEffectivePrice())
                .productNameSnapshot(product.getProductName())
                .quantity(1)
                .build());

        // 서버가 죽어 completeConfirm()이 아예 실행되지 못한 상황을 재현한다 —
        // 어떤 보상 로직도 거치지 않고 저장소에 PENDING 결제를 직접 심는다.
        pgOrderId = "webhook-order-" + System.nanoTime();
        paymentRepository.save(Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(product.getEffectivePrice())
                .paymentStatus(PaymentStatus.PENDING)
                .pgProvider("tosspayments")
                .pgOrderId(pgOrderId)
                .build());
    }

    private HttpEntity<String> webhookRequest(String transmissionId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("tosspayments-webhook-transmission-id", transmissionId);
        headers.set("tosspayments-webhook-transmission-time", "2026-08-21T00:00:00+09:00");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {
                  "eventType": "PAYMENT_STATUS_CHANGED",
                  "data": { "paymentKey": "pk-1", "orderId": "%s", "status": "DONE" }
                }
                """.formatted(pgOrderId);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void webhookReceived_asyncProcessingCompletesPaymentAndMarksEventProcessed() {
        toss.stubGetPaymentByOrderIdStatus(pgOrderId, "DONE");

        restTemplate.exchange("/api/payments/webhooks/toss", HttpMethod.POST,
                webhookRequest("tx-async-1"), Void.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findByPgOrderId(pgOrderId).orElseThrow();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.APPROVED);

            WebhookEvent event = webhookEventRepository.findAll().stream()
                    .filter(e -> "tx-async-1".equals(e.getTransmissionId()))
                    .findFirst().orElseThrow();
            assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        });
    }

    @Test
    void webhookReceived_whenPgNotDone_marksPaymentFailedAndEventProcessed() {
        toss.stubGetPaymentByOrderIdStatus(pgOrderId, "ABORTED");

        restTemplate.exchange("/api/payments/webhooks/toss", HttpMethod.POST,
                webhookRequest("tx-async-2"), Void.class);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Payment payment = paymentRepository.findByPgOrderId(pgOrderId).orElseThrow();
            assertThat(payment.getPaymentStatus()).isEqualTo(PaymentStatus.FAILED);

            WebhookEvent event = webhookEventRepository.findAll().stream()
                    .filter(e -> "tx-async-2".equals(e.getTransmissionId()))
                    .findFirst().orElseThrow();
            assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.PROCESSED);
        });
    }
}
