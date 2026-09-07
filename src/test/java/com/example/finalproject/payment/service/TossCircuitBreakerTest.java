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
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Iterator;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * toss-payment 서킷브레이커의 open/half-open/closed 전이를 실제로 검증한다.
 * TossResilienceConfig: slidingWindowSize=10, minimumNumberOfCalls=5, failureRateThreshold=50%,
 * permittedNumberOfCallsInHalfOpenState=3. application-test.yml: waitDurationInOpenStateMs=500
 * (테스트 전용, 기본 10초 대신).
 */
class TossCircuitBreakerTest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    private Long productId;


    @BeforeEach
    void setUp() {
        // 시드가 돌려준 스토어를 그대로 쓴다. findAll().findFirst() 로 아무 스토어나 집으면
        // 같은 스키마를 쓰는 다른 테스트(예: 검색 인덱스 시더)가 만든 스토어를 집을 수 있고,
        // 그 스토어는 배달 가능 거리 밖이라 prepare 가 DELIVERY_NOT_AVAILABLE 로 실패한다.
        Store store = seeder.seedStoreWithProducts(1, 100);

        productId = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();
    }

    // 결제마다 새 사용자를 쓴다. 사용자당 활성 결제는 하나뿐이라
    // (PaymentService.replaceReadyPaymentOrBlockUnresolvedPayment), 회로가 OPEN이라 미확정으로
    // 남은 결제가 있으면 같은 사용자의 다음 prepare 가 PAYMENT_IN_PROGRESS 로 막힌다.
    private String newCustomerToken() {
        String email = "cb-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");

        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");
        ResponseEntity<ApiResponse<LoginResponse>> loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<>() {});
        return loginResponse.getBody().getData().getAccessToken();
    }

    private CircuitBreaker tossPaymentCircuitBreaker() {
        // circuitBreakerFactory.create(name)만으로는 registry에 실제 인스턴스가 안 만들어진다 —
        // .run()을 최소 한 번 실행해야 등록된 커스텀 설정으로 생성된다(Task 7 학습 문서 참고).
        Resilience4JCircuitBreakerFactory factory = (Resilience4JCircuitBreakerFactory) circuitBreakerFactory;
        return factory.getCircuitBreakerRegistry().circuitBreaker("toss-payment");
    }

    private ResponseEntity<String> confirmAs(String accessToken) {
        return callConfirm(accessToken, prepareNewPayment(accessToken));
    }

    private Long prepareNewPayment(String accessToken) {
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

    private ResponseEntity<String> callConfirm(String accessToken, Long paymentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentConfirmRequest confirmRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(confirmRequest, "paymentId", paymentId);
        // Payment.paymentKey 는 UNIQUE 다. completeConfirm() 이 요청의 paymentKey 를 그대로 저장하므로
        // 고정값을 쓰면 승인에 성공하는 두 번째 결제부터 DataIntegrityViolationException 으로 500이 된다.
        // 회로는 PG 호출만 기록하고 DB 실패는 회로 밖이라, 그래도 CLOSED 단언은 통과해 실패가 가려진다.
        ReflectionTestUtils.setField(confirmRequest, "paymentKey", "test-payment-key-" + paymentId);
        return restTemplate.exchange("/api/payments/confirm", HttpMethod.POST,
                new HttpEntity<>(confirmRequest, headers), String.class);
    }

    @Test
    void circuitOpensAfterRepeatedFailures_thenRecoversToClosed() throws InterruptedException {
        toss.server.stubFor(WireMock.post(urlPathMatching("/v1/payments/confirm"))
                .willReturn(WireMock.aResponse().withStatus(500)));

        // 사용자당 활성 결제가 하나뿐이라 결제마다 사용자를 나눠야 하는데, 사용자 생성·로그인을
        // 루프 안에서 하면 한 바퀴가 waitDurationInOpenState(500ms)를 넘겨 회로가 도중에
        // HALF_OPEN으로 전이된다. 토큰은 계측 구간 밖에서 미리 만들어 둔다.
        Iterator<String> customers = IntStream.range(0, 9)
                .mapToObj(i -> newCustomerToken())
                .toList()
                .iterator();

        // minimumNumberOfCalls(5)를 채우는 것은 confirm 횟수가 아니라 회로 호출 횟수다. 승인이
        // RESULT_UNKNOWN이면 PaymentService가 같은 회로로 pgOrderId 재조회를 한 번 더 하므로
        // (9-A Task 2) confirm 1건이 회로 호출 2건을 쓴다. 그래서 3번째 confirm에서 5건이
        // 채워져 OPEN이 되고, WireMock에 도달한 confirm은 3건이다.
        for (int i = 0; i < 5; i++) {
            confirmAs(customers.next());
        }

        assertThat(tossPaymentCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestCountAfterFailures =
                toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();

        // 회로가 OPEN이므로 추가 호출은 CallNotPermittedException으로 즉시 실패해
        // WireMock까지 도달하지 않아야 한다.
        confirmAs(customers.next());
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
            // 상태 단언만으로는 부족하다 — 승인 이후 경로가 깨져도 회로는 CLOSED 로 닫힌다.
            assertThat(confirmAs(customers.next()).getStatusCode().is2xxSuccessful()).isTrue();
        }

        assertThat(tossPaymentCircuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        int requestCountAfterRecovery =
                toss.server.findAll(postRequestedFor(urlPathMatching("/v1/payments/confirm"))).size();
        assertThat(requestCountAfterRecovery).isGreaterThan(requestCountWhileOpen);
    }
}
