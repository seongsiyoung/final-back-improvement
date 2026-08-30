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
 * {@link TossCircuitBreakerWorkerOccupancyTest}와 완전히 같은 요청 패턴을, 서킷브레이커를
 * {@link PassThroughIntegrationCircuitBreakerFactory}로 우회한 상태(타임아웃만 적용)에서
 * 실행한다 — negative check 역할이다. 이 조건에서 톰캣 워커 압박·무관 API 지연이 실제로
 * 재현되지 않으면, CB 적용 조건이 통과하더라도 서킷브레이커 효과의 근거로 쓸 수 없다.
 */
@Tag("manual")
@Import(TossCircuitBreakerWorkerOccupancyBypassedTest.BypassConfig.class)
class TossCircuitBreakerWorkerOccupancyBypassedTest extends AbstractTossCircuitBreakerWorkerOccupancyTest {

    private static final int TOMCAT_MAX_THREADS = 8;

    // 톰캣 스레드 수는 규모(8-worker vs 200-worker)별로 값이 달라 상위 클래스가 아닌
    // 여기서 등록한다(AbstractTossCircuitBreakerWorkerOccupancyTest 참고).
    @DynamicPropertySource
    static void tomcatThreadsProps(DynamicPropertyRegistry registry) {
        registry.add("server.tomcat.threads.max", () -> TOMCAT_MAX_THREADS);
        registry.add("server.tomcat.threads.min-spare", () -> TOMCAT_MAX_THREADS);
    }

    @Override
    protected int tomcatMaxThreads() {
        return TOMCAT_MAX_THREADS;
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
    void confirmWithoutCircuitBreaker_keepsReoccupyingWorkersForFullTimeout() throws Exception {
        assertThat(circuitBreakerFactory)
                .as("서킷브레이커가 실제로 우회됐는지 먼저 확인한다 — 이 확인 없이 결과만 보면"
                        + " 우회가 안 됐는데 우연히 비슷한 값이 나온 것과 구분할 수 없다")
                .isInstanceOf(PassThroughIntegrationCircuitBreakerFactory.class)
                .isNotInstanceOf(org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory.class);

        runLoadAndPrintReport("CB 우회(before)");
    }
}
