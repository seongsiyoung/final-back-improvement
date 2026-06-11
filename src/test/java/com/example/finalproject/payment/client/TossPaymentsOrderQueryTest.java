package com.example.finalproject.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.TossStub;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class TossPaymentsOrderQueryTest extends IntegrationTestSupport {

    @RegisterExtension
    static TossStub toss = new TossStub();

    @DynamicPropertySource
    static void tossProps(DynamicPropertyRegistry registry) {
        registry.add("toss.payments.base-url", toss::baseUrl);
    }

    @Autowired
    private TossPaymentsClient tossPaymentsClient;

    @Test
    void getPaymentByOrderId_whenDone_returnsResponseWithDoneStatus() {
        toss.stubGetPaymentByOrderIdStatus("order-1", "DONE");

        TossConfirmResponse response = tossPaymentsClient.getPaymentByOrderId("order-1");

        assertThat(response.getStatus()).isEqualTo("DONE");
        assertThat(response.getOrderId()).isEqualTo("order-1");
    }

    @Test
    void getPaymentByOrderId_whenNotFound_throwsFeignNotFound() {
        toss.stubGetPaymentByOrderIdNotFound("order-missing");

        assertThatThrownBy(() -> tossPaymentsClient.getPaymentByOrderId("order-missing"))
                .isInstanceOf(FeignException.NotFound.class);
    }
}
