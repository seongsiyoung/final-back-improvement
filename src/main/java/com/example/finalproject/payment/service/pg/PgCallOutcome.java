package com.example.finalproject.payment.service.pg;

/**
 * PG 호출의 결과를 금융 처리 관점에서 나눈 것.
 * HTTP 상태 코드 자체가 아니라 "이 응답으로 처리 여부를 확정할 수 있는가"가 기준이다.
 */
public enum PgCallOutcome {

    /** 정상 응답. 처리됐음이 확정됐다. */
    SUCCESS,

    /** 호출 자체가 나가지 않았다. 회로차단기 OPEN 등. PG는 이 요청을 본 적이 없다. */
    NOT_SENT,

    /** PG가 요청을 받고 명확하게 거절했다. 처리되지 않았음이 확정됐다. */
    EXPLICIT_REJECTION,

    /** 처리 여부를 알 수 없다. 타임아웃·연결 유실·5xx. 성공이나 실패로 추측하지 않는다. */
    RESULT_UNKNOWN
}
