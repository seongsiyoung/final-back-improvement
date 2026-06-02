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
import com.example.finalproject.testsupport.TossStub;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentConfirmIdempotencyKeyTest extends IntegrationTestSupport {

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

    private String accessToken;
    private Long paymentId;

    @BeforeEach
    void setUp() {
        toss.stubConfirmWithDelay(Duration.ofSeconds(2));

        String email = "idem-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
        seeder.seedStoreWithProducts(1, 100);

        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", email);
        ReflectionTestUtils.setField(loginRequest, "password", "password1234!");
        var loginResponse = restTemplate.exchange(
                "/api/auth/login", HttpMethod.POST, new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<LoginResponse>>() {});
        accessToken = loginResponse.getBody().getData().getAccessToken();

        Store store = productRepository.findAll().stream().map(Product::getStore).findFirst().orElseThrow();
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
    }

    @Test
    void retryAfterTimeout_sendsSameIdempotencyKeyToToss() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        PostPaymentConfirmRequest confirmRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(confirmRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(confirmRequest, "paymentKey", "test-payment-key");
        HttpEntity<PostPaymentConfirmRequest> entity = new HttpEntity<>(confirmRequest, headers);

        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> firstResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, entity,
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(firstResponse.getBody()).isNotNull();
        assertThat(firstResponse.getBody().isSuccess()).isFalse();
        assertThat(firstResponse.getBody().getError().getCode()).isEqualTo("COMMON-000");

        toss.stubConfirmSuccess();
        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> secondResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, entity,
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getBody()).isNotNull();
        assertThat(secondResponse.getBody().isSuccess()).isTrue();
        assertThat(secondResponse.getBody().getData().getStatus()).isNotBlank();

        List<ServeEvent> confirmCalls = toss.server.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals("/v1/payments/confirm"))
                .toList();

        assertThat(confirmCalls).hasSize(2);
        String firstKey = confirmCalls.get(1).getRequest().getHeader("Idempotency-Key");
        String secondKey = confirmCalls.get(0).getRequest().getHeader("Idempotency-Key");
        assertThat(firstKey).isNotBlank().isEqualTo(secondKey);
    }

    @Test
    void retryWithDifferentPaymentKey_sendsDifferentIdempotencyKeyToToss() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        PostPaymentConfirmRequest firstRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(firstRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(firstRequest, "paymentKey", "first-payment-key");
        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> firstResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, new HttpEntity<>(firstRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        toss.stubConfirmSuccess();
        PostPaymentConfirmRequest secondRequest = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(secondRequest, "paymentId", paymentId);
        ReflectionTestUtils.setField(secondRequest, "paymentKey", "second-payment-key");
        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> secondResponse = restTemplate.exchange(
                "/api/payments/confirm", HttpMethod.POST, new HttpEntity<>(secondRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<ApiResponse<PostPaymentConfirmResponse>>() {});
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<ServeEvent> confirmCalls = toss.server.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals("/v1/payments/confirm"))
                .toList();
        assertThat(confirmCalls).hasSize(2);

        String firstKey = confirmCalls.get(1).getRequest().getHeader("Idempotency-Key");
        String secondKey = confirmCalls.get(0).getRequest().getHeader("Idempotency-Key");
        assertThat(firstKey)
                .isNotBlank()
                .doesNotContain("first-payment-key", "second-payment-key");
        assertThat(secondKey)
                .isNotBlank()
                .doesNotContain("first-payment-key", "second-payment-key");
        assertThat(firstKey).isNotEqualTo(secondKey);
    }
}
