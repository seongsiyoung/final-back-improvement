package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.PassThroughIntegrationCircuitBreakerFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * {@link TossCircuitBreakerWorkerOccupancy200Test}와 완전히 같은 요청 패턴을, 톰캣 워커 200개
 * 규모에서 서킷브레이커를 우회한 상태(타임아웃만 적용)로 실행한다 — negative check 역할이다.
 */
@Tag("manual")
@Import(TossCircuitBreakerWorkerOccupancy200BypassedTest.BypassConfig.class)
class TossCircuitBreakerWorkerOccupancy200BypassedTest extends AbstractTossCircuitBreakerWorkerOccupancyTest {

    private static final int TOMCAT_MAX_THREADS = 200;
    private static final int CONFIRM_ATTEMPT_POOL_SIZE = 230;

    @DynamicPropertySource
    static void tomcatThreadsProps(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.threads.max", () -> TOMCAT_MAX_THREADS);
        registry.add("server.tomcat.threads.min-spare", () -> TOMCAT_MAX_THREADS);
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

    @TestConfiguration
    static class BypassConfig {
        @Bean
        @Primary
        CircuitBreakerFactory<?, ?> passThroughCircuitBreakerFactory() {
            return new PassThroughIntegrationCircuitBreakerFactory();
        }
    }

    @Test
    void confirmWithoutCircuitBreaker_at200Workers_keepsReoccupyingWorkersForFullTimeout() throws Exception {
        assertThat(circuitBreakerFactory)
                .as("서킷브레이커가 실제로 우회됐는지 먼저 확인한다 — 이 확인 없이 결과만 보면"
                        + " 우회가 안 됐는데 우연히 비슷한 값이 나온 것과 구분할 수 없다")
                .isInstanceOf(PassThroughIntegrationCircuitBreakerFactory.class)
                .isNotInstanceOf(org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory.class);

        runLoadAndPrintReport("CB 우회(before) — 200 workers");
    }
}
