package com.example.finalproject.payment.service.pg;

import static org.assertj.core.api.Assertions.assertThat;

import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.FeignException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 실제 Toss 테스트 서버에 붙어 에러 응답을 받아, 그것을 분류기가 어떻게 나누는지 고정한다.
 *
 * <p>2단계의 분류 규칙은 Toss 문서만 보고 정한 것이라, 실제 응답의 상태 코드와 body 형태가
 * 가정과 같은지 확인된 적이 없었다. 그 가정이 틀리면 "결과를 모르는 것을 실패로 확정하지
 * 않는다"는 이 리팩터의 핵심 주장이 무너진다.
 *
 * <p>Toss 는 {@code TossPayments-Test-Code} 헤더로 원하는 에러를 재현해준다. 확인 결과
 * paymentKey 검증보다 이 헤더를 먼저 처리하므로, 결제위젯이 발급한 실제 paymentKey 없이도
 * 실패 응답만은 실물로 받을 수 있다. 성공 승인은 이 방법으로 만들 수 없다.
 *
 * <p>Feign 은 이 저장소에서 커스텀 ErrorDecoder 를 쓰지 않으므로 {@code ErrorDecoder.Default}
 * 가 {@code FeignException.errorStatus(...)} 로 예외를 만든다. 이 테스트도 같은 방식으로
 * 예외를 만들어 프로덕션과 같은 타입·body 를 분류기에 넣는다.
 *
 * <p>네트워크 장애(read timeout, connection 끊김)는 이 헤더로 만들 수 없다. 그쪽은
 * WireMock 기반 테스트가 계속 담당한다.
 */
@Tag("manual")
@EnabledIfEnvironmentVariable(named = "TOSS_TEST_SECRET_KEY", matches = ".+",
        disabledReason = "실제 Toss 테스트 시크릿 키가 있어야 실행된다")
class TossErrorContractManualTest {

    private static final String BASE_URL = "https://api.tosspayments.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @ParameterizedTest(name = "{0} → HTTP {1} → {2}")
    @CsvSource({
            "REJECT_CARD_PAYMENT,                       403, EXPLICIT_REJECTION",
            "ALREADY_PROCESSED_PAYMENT,                 400, RESULT_UNKNOWN",
            "IDEMPOTENT_REQUEST_PROCESSING,             409, RESULT_UNKNOWN",
            "DUPLICATED_ORDER_ID,                       400, RESULT_UNKNOWN",
            "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING, 500, RESULT_UNKNOWN",
            "COMMON_ERROR,                              500, RESULT_UNKNOWN"
    })
    @DisplayName("실제 Toss 승인 실패 응답을 분류기가 결과 종류대로 나눈다")
    void classifiesRealTossConfirmFailure(String testCode, int expectedStatus, PgCallOutcome expectedOutcome)
            throws Exception {

        HttpResponse<String> raw = callConfirm(testCode);

        assertThat(raw.statusCode())
                .as("%s 의 실제 HTTP 상태가 바뀌면 분류 규칙의 전제가 달라진다", testCode)
                .isEqualTo(expectedStatus);
        assertThat(raw.body())
                .as("분류기는 body 의 code 필드를 읽는다")
                .contains("\"code\":\"" + testCode + "\"");

        assertThat(PgFailureClassifier.classify(toFeignException(raw)))
                .isEqualTo(expectedOutcome);
    }

    @Test
    @DisplayName("존재하지 않는 주문 조회는 FeignException.NotFound 로 올라온다")
    void unknownOrderQueryBecomesNotFound() throws Exception {
        HttpResponse<String> raw = callGetPaymentByOrderId("probe-" + System.nanoTime());

        assertThat(raw.statusCode()).isEqualTo(404);
        assertThat(raw.body()).contains("\"code\":\"NOT_FOUND_PAYMENT\"");

        // 재조정은 이 타입을 직접 잡아 승인 여부를 확정한다. 타입이 달라지면 그 분기가 죽는다.
        assertThat(toFeignException(raw)).isInstanceOf(FeignException.NotFound.class);
    }

    private HttpResponse<String> callConfirm(String testCode) throws Exception {
        String body = """
                {"paymentKey":"manual-contract-probe","orderId":"probe-%d","amount":1000}
                """.formatted(System.nanoTime());
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/payments/confirm"))
                .header("Authorization", basicAuth())
                .header("Content-Type", "application/json")
                .header("TossPayments-Test-Code", testCode)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> callGetPaymentByOrderId(String orderId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "/v1/payments/orders/" + orderId))
                .header("Authorization", basicAuth())
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String basicAuth() {
        String secretKey = System.getenv("TOSS_TEST_SECRET_KEY");
        return "Basic " + Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
    }

    /** Feign 의 ErrorDecoder.Default 와 같은 방식으로 예외를 만든다. */
    private FeignException toFeignException(HttpResponse<String> raw) {
        Request request = Request.create(Request.HttpMethod.POST, raw.uri().toString(),
                Collections.emptyMap(), new byte[0], StandardCharsets.UTF_8, new RequestTemplate());
        Response response = Response.builder()
                .status(raw.statusCode())
                .reason(null)
                .request(request)
                .headers(Collections.emptyMap())
                .body(raw.body(), StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("TossPaymentsClient#manualContract", response);
    }
}
