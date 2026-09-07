package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.auth.dto.request.LoginRequest;
import com.example.finalproject.auth.dto.response.LoginResponse;
import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentConfirmResponse;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentConfirmIdempotencyKeyTest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private ProductRepository productRepository;

    private String accessToken;
    private Long paymentId;

    @BeforeEach
    void setUp() {
        toss.stubConfirmWithDelay(Duration.ofSeconds(2));

        String email = "idem-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
        // 시드가 돌려준 스토어를 그대로 쓴다. findAll().findFirst() 로 아무 스토어나 집으면
        // 같은 스키마를 쓰는 다른 테스트(예: 검색 인덱스 시더)가 만든 스토어를 집을 수 있고,
        // 그 스토어는 배달 가능 거리 밖이라 prepare 가 DELIVERY_NOT_AVAILABLE 로 실패한다.
        Store store = seeder.seedStoreWithProducts(1, 100);

        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");
        var loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<LoginResponse>>() {});
        accessToken = loginResponse.getBody().getData().getAccessToken();

        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentPrepareRequest prepareRequest = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(prepareRequest, "productQuantities", Map.of(product.getId(), 1));
        ReflectionTestUtils.setField(prepareRequest, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(prepareRequest, "deliveryAddress", "서울시 강남구 테헤란로 123");
        var prepareResponse = restTemplate.exchange(
                "/api/payments/prepare", HttpMethod.POST, new HttpEntity<>(prepareRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentPrepareResponse>>() {});
        paymentId = prepareResponse.getBody().getData().getPaymentId();

        // 이 시나리오의 전제는 "Toss 가 승인 요청을 받은 적이 없다"는 것이다. 타임아웃 뒤
        // PaymentService.lookupPaymentOnce() 가 pgOrderId 로 되묻는데, 그 응답을 WireMock 의
        // 기본 404 에 맡기면 전제가 코드에 남지 않는다. 명시적으로 스텁한다.
        toss.stubGetPaymentByOrderIdNotFound(prepareResponse.getBody().getData().getPgOrderId());
    }

    @Test
    void retryAfterTimeout_doesNotSendSecondConfirmationToToss() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentConfirmRequest confirmRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(confirmRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(confirmRequest, "paymentKey", "test-payment-key");
        HttpEntity<PostPaymentConfirmRequest> entity = new HttpEntity<>(confirmRequest, headers);

        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> firstResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, entity,
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        // 읽기 타임아웃은 RESULT_UNKNOWN 이다. 그 직후 pgOrderId 로 Toss 에 되묻고(setUp 에서
        // 404 로 스텁해 둔 경로), 기록이 없다는 응답을 받는다.
        // PENDING 에서의 404 는 "승인된 적 없음"이 확정된 것이므로(FLOWS.md) 결제를 FAILED 로
        // 종결하고 PAYMENT-010 을 돌려준다. 미확정인 채로 500 을 흘리던 옛 동작이 아니다.
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(firstResponse.getBody()).isNotNull();
        assertThat(firstResponse.getBody().isSuccess()).isFalse();
        assertThat(firstResponse.getBody().getError().getCode()).isEqualTo("PAYMENT-010");

        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> secondResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, entity,
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody().isSuccess()).isFalse();

        List<ServeEvent> confirmCalls = toss.server.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals("/v1/payments/confirm"))
                .toList();

        assertThat(confirmCalls).hasSize(1);
    }

    @Test
    void retryWithDifferentPaymentKey_doesNotSendSecondConfirmationToToss() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        PostPaymentConfirmRequest firstRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(firstRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(firstRequest, "paymentKey", "first-payment-key");
        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> firstResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, new HttpEntity<>(firstRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        PostPaymentConfirmRequest secondRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(secondRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(secondRequest, "paymentKey", "second-payment-key");
        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> secondResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, new HttpEntity<>(secondRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody().isSuccess()).isFalse();

        List<ServeEvent> confirmCalls = toss.server.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals("/v1/payments/confirm"))
                .toList();
        assertThat(confirmCalls).hasSize(1);
    }
}
