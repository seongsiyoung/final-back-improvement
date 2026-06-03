package com.example.finalproject.payment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossResilienceConfig {

    @Value("${toss.circuit-breaker.wait-duration-in-open-state-ms:10000}")
    private long waitDurationInOpenStateMs;

    /**
     * 서킷브레이커 인스턴스 2개.
     * - toss-payment: confirm/cancel(결제 승인·취소, 대량·고빈도)
     * - toss-billing: issueBillingKey/deleteBillingKey/approveBilling(빌링키·구독 결제, 저빈도)
     * 하나로 통합하지 않는 이유: 결제 승인 경로 장애가 빌링키 발급 같은 무관한 기능까지
     * 회로를 열어버리는 것을 막기 위함(영향 범위 분리).
     *
     * TimeLimiter는 의도적으로 설정하지 않는다 — Resilience4j의 TimeLimiter는 supplier를
     * 별도 스레드풀에서 비동기로 실행하고 호출 스레드는 Future.get(timeout)으로 대기하는
     * 방식이라, PG 호출 1건마다 톰캣 워커 스레드 + TimeLimiter 실행 스레드 2개를 점유하게
     * 된다. 시간 제한은 이미 Feign read-timeout(TossFeignConfig, 3초)이 동기적으로(같은
     * 스레드 안에서) 걸어주고 있어 TimeLimiter가 없어도 무제한 대기가 생기지 않는다 —
     * 스레드를 이중으로 쓰면서까지 얻을 추가 이득이 없다.
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> tossCircuitBreakerCustomizer() {
        CircuitBreakerConfig sharedConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenStateMs))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        return factory -> factory.configure(
                builder -> builder.circuitBreakerConfig(sharedConfig),
                "toss-payment", "toss-billing");
    }
}
