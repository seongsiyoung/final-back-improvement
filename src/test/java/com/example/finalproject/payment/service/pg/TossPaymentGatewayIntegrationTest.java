package com.example.finalproject.payment.service.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.TossStub;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class TossPaymentGatewayIntegrationTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private PaymentGateWay paymentGateWay;

    @Test
    void cancel_whenTossReturnsCanceledAmount_returnsCumulativeCanceledAmount() {
        toss.stubCancelSuccess(3000, 2000);

        CancelResult result = paymentGateWay.cancel("payment-key-1", 1000, "고객 변심", "cancel-key-1");

        assertThat(result.getCumulativeCanceledAmount()).isEqualTo(1000);
        toss.server.verify(postRequestedFor(urlPathEqualTo("/v1/payments/payment-key-1/cancel"))
                .withHeader("Idempotency-Key", equalTo("cancel-key-1"))
                .withRequestBody(equalToJson("""
                        { "cancelReason": "고객 변심", "cancelAmount": 1000 }
                        """)));
    }
}
