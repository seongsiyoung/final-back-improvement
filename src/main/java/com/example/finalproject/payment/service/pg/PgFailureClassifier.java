package com.example.finalproject.payment.service.pg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.util.Set;

/**
 * Toss 호출에서 올라온 예외를 금융 처리 관점의 결과로 옮긴다.
 *
 * <p>4xx라고 전부 거절이 아니다. Toss는 이미 승인·취소된 건에도 400을 준다.
 * 그것까지 거절로 확정하면 돈이 빠진 결제를 실패로 적는다.
 *
 * <p>분류할 수 없는 예외는 {@link PgCallOutcome#RESULT_UNKNOWN}으로 보낸다.
 * 모르는 것을 실패로 확정하면 이미 승인된 결제에 재결제를 열어주게 된다.
 */
public final class PgFailureClassifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String ALREADY_PREFIX = "ALREADY_";
    private static final Set<String> AMBIGUOUS_CLIENT_ERROR_CODES = Set.of(
            "IDEMPOTENT_REQUEST_PROCESSING",
            "DUPLICATED_ORDER_ID");

    private PgFailureClassifier() {
    }

    public static PgCallOutcome classify(Throwable throwable) {
        if (throwable == null) {
            return PgCallOutcome.SUCCESS;
        }

        if (throwable instanceof CallNotPermittedException) {
            return PgCallOutcome.NOT_SENT;
        }

        if (throwable instanceof RetryableException) {
            return PgCallOutcome.RESULT_UNKNOWN;
        }

        if (throwable instanceof FeignException.FeignClientException e) {
            return isAmbiguousOutcomeCode(e) ? PgCallOutcome.RESULT_UNKNOWN : PgCallOutcome.EXPLICIT_REJECTION;
        }

        if (throwable instanceof FeignException.FeignServerException) {
            return PgCallOutcome.RESULT_UNKNOWN;
        }

        return PgCallOutcome.RESULT_UNKNOWN;
    }

    private static boolean isAmbiguousOutcomeCode(FeignException e) {
        String body = e.contentUTF8();
        if (body == null || body.isBlank()) {
            return false;
        }
        try {
            JsonNode code = OBJECT_MAPPER.readTree(body).get("code");
            return code != null && (code.asText().startsWith(ALREADY_PREFIX)
                    || AMBIGUOUS_CLIENT_ERROR_CODES.contains(code.asText()));
        } catch (Exception ignored) {
            return false;
        }
    }
}
