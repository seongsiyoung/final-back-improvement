package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.auth.dto.request.LoginRequest;
import com.example.finalproject.auth.dto.response.LoginResponse;
import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.TossStub;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 톰캣 워커(max=8)보다 많은 confirm 요청(15건)을 동시에 발사해, 무관한 API(categories)가
 * 두 번째 워커 라운드까지 밀려 대기해야 하는 상황을 만든다. 15건 + categories(16번째로 큐잉)를
 * 워커 8개로 처리하면 categories는 ceil(16/8)=2번째 라운드에서 처리된다.
 *
 * - Feign read-timeout(1000ms)이 적용된 상태: 1라운드 ≈ 1000ms에 끝나고 categories는
 *   그 시점(대기 시작으로부터 ≈ 800ms) 직후 처리된다.
 * - read-timeout이 없다면(회귀 상황): 1라운드가 WireMock 지연(2000ms) 전체를 다 기다려야
 *   끝나므로 categories는 ≈ 1800ms를 기다린다.
 *
 * 두 시나리오 사이(400ms~1300ms)에 상한/하한을 모두 걸어, "워커가 실제로 소진됐다"와
 * "타임아웃 덕분에 빨리 풀렸다"를 동시에 증명한다 — 상한만 걸면 워커 소진 자체가 안 일어나도
 * (레이스로 인해) 통과해버려 아무것도 증명 못 하는 테스트가 된다.
 */
class ThreadExhaustionTest extends IntegrationTestSupport {

    private static final int CONCURRENT_CONFIRMS = 15;
    private static final int TOMCAT_MAX_THREADS = 8;

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
        // 이 테스트에만 적용 — application-test.yml(전역)을 건드리면 다른 통합 테스트의
        // 톰캣 워커 수까지 줄어들어 영향 범위가 필요 이상으로 넓어진다.
        registry.add("server.tomcat.threads.max", () -> TOMCAT_MAX_THREADS);
        registry.add("server.tomcat.threads.min-spare", () -> TOMCAT_MAX_THREADS);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private ProductRepository productRepository;

    private String accessToken;
    private Long productId;

    @BeforeEach
    void setUp() {
        String email = "exhaustion-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
        seeder.seedStoreWithProducts(1, 1000);

        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");
        ResponseEntity<ApiResponse<LoginResponse>> loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        accessToken = loginResponse.getBody().getData().getAccessToken();

        Store store = productRepository.findAll().stream().map(Product::getStore).findFirst().orElseThrow();
        productId = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();
    }

    private Long prepareNewPayment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentPrepareRequest prepareRequest = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(prepareRequest, "productQuantities", Map.of(productId, 1));
        ReflectionTestUtils.setField(prepareRequest, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(prepareRequest, "deliveryAddress", "서울시 강남구 테헤란로 123");
        ResponseEntity<ApiResponse<PostPaymentPrepareResponse>> prepareResponse = restTemplate.exchange(
                "/api/payments/prepare", HttpMethod.POST, new HttpEntity<>(prepareRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        return prepareResponse.getBody().getData().getPaymentId();
    }

    private void callConfirm(Long paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentConfirmRequest confirmRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(confirmRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(confirmRequest, "paymentKey", "test-payment-key");
        try {
            restTemplate.exchange("/api/payments/confirm", HttpMethod.POST,
                    new HttpEntity<>(confirmRequest, headers), String.class);
        } catch (Exception e) {
            // 타임아웃으로 인한 5xx 응답 자체는 이 테스트의 관심사가 아니다(워커 점유 시간만 본다) —
            // 다만 예상 밖의 예외를 조용히 묻지 않도록 최소한 표준 에러 스트림에는 남긴다.
            System.err.println("confirm 호출 중 예외(관찰 목적상 무시): " + e);
        }
    }

    @Test
    void unrelatedApi_staysResponsive_whileTossConfirmIsSlow() throws Exception {
        toss.stubConfirmWithDelay(Duration.ofSeconds(2));

        List<Long> paymentIds = IntStream.range(0, CONCURRENT_CONFIRMS)
                .mapToObj(i -> prepareNewPayment())
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CONFIRMS);
        try {
            List<Future<?>> confirmCalls = new ArrayList<>();
            for (Long paymentId : paymentIds) {
                confirmCalls.add(pool.submit(() -> callConfirm(paymentId)));
            }

            // confirm 15건이 톰캣 커넥터 큐에 전부 접수될 시간을 준다 — categories가 반드시
            // 큐의 맨 뒤(16번째)에 서도록 만들기 위함.
            Thread.sleep(200);

            long start = System.currentTimeMillis();
            ResponseEntity<String> categoriesResponse =
                    restTemplate.getForEntity("/api/stores/categories", String.class);
            long elapsedMs = System.currentTimeMillis() - start;

            assertThat(categoriesResponse.getStatusCode().is2xxSuccessful()).isTrue();
            // 하한: 워커가 실제로 소진돼 categories가 즉시 처리되지 않고 대기했음을 증명한다.
            assertThat(elapsedMs).isGreaterThan(400);
            // 상한: read-timeout(1000ms) 덕분에 1라운드가 빨리 끝나 categories가
            // WireMock 지연(2초) 전체를 기다리지 않았음을 증명한다.
            assertThat(elapsedMs).isLessThan(1300);

            for (Future<?> f : confirmCalls) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(3, TimeUnit.SECONDS);
        }
    }
}
