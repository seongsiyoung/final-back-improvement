package com.example.finalproject.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
        stubCancelSuccess(1000, 0);
    }

    public void stubCancelSuccess(int totalAmount, int balanceAmount) {
        server.stubFor(post(urlPathMatching("/v1/payments/.*/cancel"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "test-payment-key",
                                  "status": "CANCELED",
                                  "totalAmount": %d,
                                  "balanceAmount": %d
                                }
                                """.formatted(totalAmount, balanceAmount))));
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

    public void stubGetPaymentByOrderIdStatus(String orderId, String status) {
        // paymentKey를 orderId 기반으로 만든다 — Payment.paymentKey는 UNIQUE 제약이 있어서,
        // 서로 다른 결제 여러 건을 한 테스트에서 동시에 재조회할 때 같은 값을 재사용하면
        // completeConfirm()의 approve() 저장 단계에서 제약 위반이 난다.
        server.stubFor(get(urlPathEqualTo("/v1/payments/orders/" + orderId))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentKey": "pk-%s",
                                  "orderId": "%s",
                                  "totalAmount": 10000,
                                  "status": "%s",
                                  "approvedAt": "2026-08-17T00:00:00+09:00",
                                  "receipt": { "url": "https://dashboard.tosspayments.com/receipt/test" }
                                }
                                """.formatted(orderId, orderId, status))));
    }

    public void stubGetPaymentByOrderIdNotFound(String orderId) {
        server.stubFor(get(urlPathEqualTo("/v1/payments/orders/" + orderId))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                { "code": "NOT_FOUND_PAYMENT", "message": "존재하지 않는 결제 정보입니다." }
                                """)));
    }
}
