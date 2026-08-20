package com.example.finalproject.testsupport;

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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class PaymentConfirmHarnessTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @org.springframework.test.context.DynamicPropertySource
    static void tossProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private ProductRepository productRepository;

    private String email;

    @BeforeEach
    void setUp() {
        toss.stubConfirmSuccess();
        toss.stubCancelSuccess();
        email = "harness-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "password1234!");
        seeder.seedStoreWithProducts(1, 100);
    }

    @Test
    void loginPrepareConfirmSucceeds() {
        LoginRequest loginRequest = new LoginRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(loginRequest, "email", email);
        org.springframework.test.util.ReflectionTestUtils.setField(loginRequest, "password", "password1234!");

        ResponseEntity<ApiResponse<LoginResponse>> loginResponse = restTemplate.exchange(
                "/api/auth/login", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(loginRequest),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResponse.getBody().getData().getAccessToken();

        Store store = productRepository.findAll().stream()
                .map(Product::getStore)
                .findFirst()
                .orElseThrow();
        Product product = productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);

        PostPaymentPrepareRequest prepareRequest = new PostPaymentPrepareRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(
                prepareRequest, "productQuantities", Map.of(product.getId(), 1));
        org.springframework.test.util.ReflectionTestUtils.setField(
                prepareRequest, "paymentMethod", PaymentMethodType.CARD);
        org.springframework.test.util.ReflectionTestUtils.setField(
                prepareRequest, "deliveryAddress", "서울시 강남구 테헤란로 123");

        ResponseEntity<ApiResponse<PostPaymentPrepareResponse>> prepareResponse = restTemplate.exchange(
                "/api/payments/prepare", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(prepareRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(prepareResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Long paymentId = prepareResponse.getBody().getData().getPaymentId();

        PostPaymentConfirmRequest confirmRequest = new PostPaymentConfirmRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(confirmRequest, "paymentId", paymentId);
        org.springframework.test.util.ReflectionTestUtils.setField(
                confirmRequest, "paymentKey", "test-payment-key");

        ResponseEntity<ApiResponse<PostPaymentConfirmResponse>> confirmResponse = restTemplate.exchange(
                "/api/payments/confirm", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(confirmRequest, headers),
                new org.springframework.core.ParameterizedTypeReference<>() {});

        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmResponse.getBody().getData().getStatus()).isNotBlank();
    }
}
