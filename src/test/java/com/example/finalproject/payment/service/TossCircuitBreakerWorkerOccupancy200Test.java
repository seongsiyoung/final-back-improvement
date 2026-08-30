package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link TossCircuitBreakerWorkerOccupancyTest}와 같은 검증을, 실제 운영값에 가까운 톰캣 워커
 * 200개 규모에서 재현한다. 8-worker 조건(위 클래스)은 결정적 재현을 위해 워커 수를 의도적으로
 * 줄인 것이었고, 이 클래스는 그 축소가 결과를 왜곡하지 않았는지 더 큰 규모에서 교차 검증한다.
 *
 * <p>워커 200개를 실제로 포화시키려면 confirm 동시성도 그만큼 커야 한다({@link #confirmAttemptPoolSize()}
 * 참고) — 그 규모에서는 클라이언트(HTTP 커넥션 생성)와 서버 DB 커넥션 풀(HikariCP, test
 * 프로파일 기본값 2)이 톰캣보다 먼저 병목될 위험이 있어, HikariCP 풀을 이 테스트에서만
 * {@link #tomcatThreadsProps} 아래에서 넉넉히 올려두고, 상위 클래스의 in-flight negative check로
 * 클라이언트가 실제로 그 동시성에 도달했는지까지 확인한다.
 */
@Tag("manual")
class TossCircuitBreakerWorkerOccupancy200Test extends AbstractTossCircuitBreakerWorkerOccupancyTest {

    private static final int TOMCAT_MAX_THREADS = 200;
    // 톰캣 워커 200개를 실제로 다 채우려면 accept 대기분까지 감안해 워커 수보다 확실히 많은
    // 동시 confirm 시도가 필요하다.
    private static final int CONFIRM_ATTEMPT_POOL_SIZE = 230;

    @DynamicPropertySource
    static void tomcatThreadsProps(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.threads.max", () -> TOMCAT_MAX_THREADS);
        registry.add("server.tomcat.threads.min-spare", () -> TOMCAT_MAX_THREADS);
        // prepareNewPayment()는 confirm과 별개로 DB에 짧게 쓴다. 230개 동시 시도가 한꺼번에
        // prepare를 호출하는 순간, test 프로파일 기본 HikariCP 풀(2)로는 그 자체가 톰캣 워커를
        // 오래 붙잡는 새 병목이 될 수 있어(커넥션 대기 중에도 워커는 점유된 채다) 이 테스트에서만
        // 올린다 — 8-worker 조건은 동시성이 10이라 이 문제가 드러나지 않았다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 30);
    }

    @Override
    protected int tomcatMaxThreads() {
        return TOMCAT_MAX_THREADS;
    }

    @Override
    protected int confirmAttemptPoolSize() {
        return CONFIRM_ATTEMPT_POOL_SIZE;
    }

    @Test
    void circuitBreakerLimitsWorkerOccupancy_at200Workers_afterOutageDetected() throws Exception {
        assertThat(circuitBreakerFactory)
                .as("이 테스트는 실제 서킷브레이커가 적용된 상태를 전제로 한다")
                .isInstanceOf(Resilience4JCircuitBreakerFactory.class);

        runLoadAndPrintReport("CB 적용(after) — 200 workers");
    }
}
