package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;

/**
 * read-timeout 60초(prod와 동일) 조건, 서킷브레이커 적용 상태(현재 코드 그대로)에서
 * Toss 장애 발생 시 톰캣 워커 점유가 실제로 억제되는지 확인한다.
 *
 * <p>기대되는 흐름(자세한 설계 근거는 {@link AbstractTossCircuitBreakerWorkerOccupancyTest} 참고):
 * <ol>
 *   <li>초기 구간: 아직 실패가 5건 쌓이기 전(minimumNumberOfCalls=5)이라 CLOSED 상태 그대로
 *       confirm이 워커를 최대 60초씩 점유한다 — 이 구간은 서킷브레이커가 막을 수 없는
 *       초기 장애 구간으로 별도 기록한다.</li>
 *   <li>OPEN 전이 이후: 신규 confirm이 Toss 호출 없이 즉시 실패 처리되어 워커 재점유가
 *       억제되고, 톰캣 busy/카테고리 응답시간이 회복돼야 한다.</li>
 *   <li>waitDurationInOpenState(10초, 기본값) 경과 후 HALF_OPEN: 최대 3건의 probe가 다시
 *       Toss에 진입해 워커를 재점유할 수 있다 — 정상 동작이며, Toss 장애가 지속되면 다시
 *       OPEN으로 돌아간다.</li>
 * </ol>
 */
@Tag("manual")
class TossCircuitBreakerWorkerOccupancyTest extends AbstractTossCircuitBreakerWorkerOccupancyTest {

    @Test
    void circuitBreakerLimitsWorkerOccupancy_afterOutageDetected() throws Exception {
        assertThat(circuitBreakerFactory)
                .as("이 테스트는 실제 서킷브레이커가 적용된 상태를 전제로 한다")
                .isInstanceOf(Resilience4JCircuitBreakerFactory.class);

        runLoadAndPrintReport("CB 적용(after)");
    }
}
