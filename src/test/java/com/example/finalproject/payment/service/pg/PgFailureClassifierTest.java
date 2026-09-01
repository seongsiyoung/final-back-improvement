package com.example.finalproject.payment.service.pg;

import static org.assertj.core.api.Assertions.assertThat;

import feign.FeignException;
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PgFailureClassifierTest {

    private final Request request = Request.create(
            HttpMethod.POST, "/v1/payments/confirm", Collections.emptyMap(),
            new byte[0], StandardCharsets.UTF_8, new RequestTemplate());

    private FeignException badRequest(String body) {
        return new FeignException.BadRequest(
                "bad request", request,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                Collections.emptyMap());
    }

    @Test
    @DisplayName("회로차단기가 열려 호출이 나가지 않으면 NOT_SENT")
    void circuitOpenIsNotSent() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("toss-payment");
        Throwable e = CallNotPermittedException.createCallNotPermittedException(circuitBreaker);

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.NOT_SENT);
    }

    @Test
    @DisplayName("카드사 거절 4xx 는 명확한 거절")
    void cardRejectionIsExplicitRejection() {
        Throwable e = badRequest("{\"code\":\"REJECT_CARD_COMPANY\",\"message\":\"카드사에서 거절했습니다.\"}");

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.EXPLICIT_REJECTION);
    }

    @Test
    @DisplayName("이미 처리됐다는 4xx 는 거절이 아니라 RESULT_UNKNOWN — 돈이 빠졌을 수 있다")
    void alreadyProcessedIsResultUnknown() {
        Throwable e = badRequest("{\"code\":\"ALREADY_PROCESSED_PAYMENT\",\"message\":\"이미 처리된 결제 입니다.\"}");

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("본문이 없는 4xx 는 기본값인 명확한 거절")
    void clientErrorWithoutBodyIsExplicitRejection() {
        Throwable e = badRequest(null);

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.EXPLICIT_REJECTION);
    }

    @Test
    @DisplayName("Toss 5xx 는 처리 여부를 알 수 없으므로 RESULT_UNKNOWN")
    void serverErrorIsResultUnknown() {
        Throwable e = new FeignException.InternalServerError("server error", request, null, null);

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("읽기 타임아웃은 RESULT_UNKNOWN")
    void readTimeoutIsResultUnknown() {
        Throwable e = new RetryableException(
                -1, "read timed out", HttpMethod.POST,
                new SocketTimeoutException("Read timed out"), (Long) null, request);

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("TimeLimiter 초과가 RuntimeException 으로 감싸져 와도 RESULT_UNKNOWN")
    void wrappedTimeoutIsResultUnknown() {
        Throwable e = new RuntimeException(new TimeoutException("TimeLimiter"));

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("분류할 수 없는 예외는 보수적으로 RESULT_UNKNOWN")
    void unknownExceptionIsResultUnknown() {
        Throwable e = new IllegalStateException("unexpected");

        assertThat(PgFailureClassifier.classify(e)).isEqualTo(PgCallOutcome.RESULT_UNKNOWN);
    }

    @Test
    @DisplayName("예외가 없으면 SUCCESS")
    void nullIsSuccess() {
        assertThat(PgFailureClassifier.classify(null)).isEqualTo(PgCallOutcome.SUCCESS);
    }
}
