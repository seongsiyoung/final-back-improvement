package com.example.finalproject.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.testsupport.IntegrationTestSupport;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

class TossResilienceConfigTest extends IntegrationTestSupport {

    @Autowired
    private CircuitBreakerFactory<?, ?> circuitBreakerFactory;

    @Test
    void tossPaymentAndTossBillingInstancesAreConfigured() {
        assertThat(circuitBreakerFactory.create("toss-payment")).isNotNull();
        assertThat(circuitBreakerFactory.create("toss-billing")).isNotNull();
    }

    @Test
    void tossInstancesUseCustomCircuitBreakerConfig_notDefaults() {
        // CircuitBreakerFactory.create(name)은 Spring 쪽 래퍼 객체만 만들 뿐, 실제 resilience4j
        // CircuitBreaker는 registry에 아직 생성되지 않는다 — .run()을 처음 호출하는 시점에야
        // registry.circuitBreaker(id, config, tags)가 실행되며 설정이 적용된 인스턴스가 만들어진다.
        // (참고: registry에서 이름만으로 조회하면(circuitBreaker(name)) registry의 "기본" 설정으로
        // 새로 만들어버려, 우리가 지정한 커스텀 설정과 무관한 인스턴스가 생긴다 — 그래서 반드시
        // run()을 먼저 실행해 우리 설정으로 등록되게 한 다음에 조회해야 한다.)
        circuitBreakerFactory.create("toss-payment").run(() -> "ok");
        circuitBreakerFactory.create("toss-billing").run(() -> "ok");

        // Resilience4JCircuitBreakerFactory는 자체 CircuitBreakerRegistry를 관리한다 —
        // 별도로 주입받은 CircuitBreakerRegistry 빈은 이 팩토리가 실제로 쓰는 레지스트리와
        // 다를 수 있어(기본값만 보이는 다른 인스턴스), 반드시 팩토리에서 직접 꺼내야 한다.
        Resilience4JCircuitBreakerFactory resilienceFactory = (Resilience4JCircuitBreakerFactory) circuitBreakerFactory;

        for (String name : new String[] {"toss-payment", "toss-billing"}) {
            CircuitBreakerConfig config = resilienceFactory.getCircuitBreakerRegistry()
                    .circuitBreaker(name).getCircuitBreakerConfig();

            assertThat(config.getSlidingWindowSize()).isEqualTo(10);
            assertThat(config.getMinimumNumberOfCalls()).isEqualTo(5);
            assertThat(config.getFailureRateThreshold()).isEqualTo(50.0f);
            assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(3);
        }
    }
}
