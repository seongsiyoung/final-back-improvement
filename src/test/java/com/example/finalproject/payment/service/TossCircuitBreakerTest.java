package com.example.finalproject.payment.service;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * toss-payment 서킷브레이커의 open/half-open/closed 전이를 실제로 검증한다.
 * TossResilienceConfig: slidingWindowSize=10, minimumNumberOfCalls=5, failureRateThreshold=50%,
 * permittedNumberOfCallsInHalfOpenState=3. application-test.yml: waitDurationInOpenStateMs=500
 * (테스트 전용, 기본 10초 대신).
 */
class TossCircuitBreakerTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private String accessToken;
    private Long productId;

    @BeforeEach
    void setUp() {
        String email = "cb-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
        seeder.seedStoreWithProducts(1, 100);

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

    private CircuitBreaker tossPaymentCircuitBreaker() {
        // circuitBreakerFactory.create(name)만으로는 registry에 실제 인스턴스가 안 만들어진다 —
        // .run()을 최소 한 번 실행해야 등록된 커스텀 설정으로 생성된다(Task 7 학습 문서 참고).
        Resilience4JCircuitBreakerFactory factory = (Resilience4JCircuitBreakerFactory) circuitBreakerFactory;
        return factory.getCircuitBreakerRegistry().circuitBreaker("toss-payment");
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
        restTemplate.exchange("/api/payments/confirm", HttpMethod.POST,
                new HttpEntity<>(confirmRequest, headers), String.class);
    }

    @Test
    void circuitOpensAfterRepeatedFailures_thenRecoversToClosed() throws InterruptedException {
        toss.server.stubFor(WireMock.post(urlPathMatching("/v1/payments/confirm"))
                .willReturn(WireMock.aResponse().withStatus(500)));

        // minimumNumberOfCalls(5) — 5번째 호출이 끝나는 순간 실패율 100%로 평가되어 OPEN 전이가
        // 일어난다. 순차(블로킹) 호출이라 레이스 없이 결정적이다.
        for (int i = 0; i < 5; i++) {
            callConfirm(prepareNewPayment());
        }

        assertThat(tossPaymentCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestCountAfterFailures =
                toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();

        // 회로가 OPEN이므로 추가 호출은 CallNotPermittedException으로 즉시 실패해
        // WireMock까지 도달하지 않아야 한다.
        callConfirm(prepareNewPayment());
        int requestCountWhileOpen =
                toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();

        assertThat(requestCountWhileOpen).isEqualTo(requestCountAfterFailures);

        // resilience4j는 OPEN→HALF_OPEN 자동 전이를 기본적으로 쓰지 않는다
        // (automaticTransitionFromOpenToHalfOpenEnabled=false, 기본값) — waitDurationInOpenState가
        // 지나도 "다음 호출 시도"가 있어야 그 시점에 전이 여부를 판정한다. 그래서 getState()만
        // 폴링해서는 절대 바뀌지 않고, 실제로 호출을 한 번 시도해야 한다. 500ms(테스트 오버라이드)
        // 보다 확실히 긴 700ms를 먼저 기다린 뒤 호출을 시도한다.
        Thread.sleep(700);

        // permittedNumberOfCallsInHalfOpenState(3) — HALF_OPEN 진입 후 이 시행 횟수를 전부
        // 채워야 CLOSED로 재평가된다. 1건만 성공시키면 아직 HALF_OPEN에 머물러 있을 수 있어,
        // 완전한 CLOSED 복귀를 증명하려면 3건 모두 성공시켜야 한다.
        toss.stubConfirmSuccess();
        for (int i = 0; i < 3; i++) {
            callConfirm(prepareNewPayment());
        }

        assertThat(tossPaymentCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        int requestCountAfterRecovery =
                toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();
        assertThat(requestCountAfterRecovery).isGreaterThan(requestCountWhileOpen);
    }
}
