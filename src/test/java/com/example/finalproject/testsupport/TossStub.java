package com.example.finalproject.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.time.Duration;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * WireMock 기반 Toss Payments 대역. {@code TossStub} 자체를 {@code @RegisterExtension}으로
 * 등록할 수 있도록 내부 {@link WireMockExtension}의 JUnit5 생명주기 콜백을 위임한다.
 */
public class TossStub implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    public final WireMockExtension server = WireMockExtension.newInstance()
            .options(WireMockConfiguration.wireMockConfig().dynamicPort())
            .build();

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        server.beforeAll(context);
    }

    @Override
    public void afterAll(ExtensionContext context) throws Exception {
        server.afterAll(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        server.beforeEach(context);
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        server.afterEach(context);
    }

    public String baseUrl() {
        return server.baseUrl();
    }

    public void stubConfirmSuccess() {
        server.stubFor(post(urlPathMatching("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "test-payment-key",
                                  "orderId": "test-order-id",
                                  "totalAmount": 10000,
                                  "status": "DONE",
                                  "approvedAt": "2026-08-17T00:00:00+09:00",
                                  "receipt": { "url": "https://dashboard.tosspayments.com/receipt/test" }
                                }
                                """)));
    }

    public void stubConfirmWithDelay(Duration delay) {
        server.stubFor(post(urlPathMatching("/v1/payments/confirm"))
                .willReturn(aResponse()
                        .withFixedDelay((int) delay.toMillis())
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "test-payment-key",
                                  "orderId": "test-order-id",
                                  "totalAmount": 10000,
                                  "status": "DONE",
                                  "approvedAt": "2026-08-17T00:00:00+09:00",
                                  "receipt": { "url": "https://dashboard.tosspayments.com/receipt/test" }
                                }
                                """)));
    }

    public void stubCancelSuccess() {
        server.stubFor(post(urlPathMatching("/v1/payments/.*/cancel"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "paymentKey": "test-payment-key", "status": "CANCELED" }
                                """)));
    }

    public void stubIssueBillingKeySuccess() {
        server.stubFor(post(urlPathMatching("/v1/billing/authorizations/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "billingKey": "stub-billing-key",
                                  "customerKey": "customer-1",
                                  "card": { "issuerCode": "61", "acquirerCode": "31", "number": "1234", "cardType": "credit", "ownerType": "personal" }
                                }
                                """)));
    }

    public void stubDeleteBillingKeySuccess() {
        server.stubFor(delete(urlPathMatching("/v1/billing/[^/]+"))
                .willReturn(aResponse().withStatus(204)));
    }

    public void stubApproveBillingSuccess() {
        stubApproveBillingSuccess("stub-payment-key");
    }

    public void stubApproveBillingSuccess(String paymentKey) {
        server.stubFor(post(urlPathMatching("/v1/billing/[^/]+"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "%s", "orderId": "stub-order", "orderName": "구독",
                                  "status": "DONE", "approvedAt": "2026-08-20T00:00:00+09:00",
                                  "card": { "issuerCode": "61", "acquirerCode": "31", "number": "1234", "cardType": "credit", "ownerType": "personal" }
                                }
                                """.formatted(paymentKey))));
    }
}
