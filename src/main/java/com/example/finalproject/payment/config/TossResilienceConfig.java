package com.example.finalproject.payment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TossResilienceConfig {

    // TimeLimiter가 Feign read-timeout보다 항상 늦게 끊기도록 두는 여유 시간.
    // 이 값 자체를 따로 튜닝할 이유는 없어서 설정값으로 안 빼고 상수로 고정한다 —
    // "Feign이 먼저 끊는다"는 목적만 지키면 되므로 정확한 크기는 중요하지 않다.
    private static final long TIME_LIMITER_BUFFER_MS = 2000;

    @Value("${toss.circuit-breaker.wait-duration-in-open-state-ms:10000}")
    private long waitDurationInOpenStateMs;

    @Value("${spring.cloud.openfeign.client.config.tossPaymentsClient.read-timeout:3000}")
    private long tossReadTimeoutMs;

    /**
     * 서킷브레이커 인스턴스 2개.
     * - toss-payment: confirm/cancel(결제 승인·취소, 대량·고빈도)
     * - toss-billing: issueBillingKey/deleteBillingKey/approveBilling(빌링키·구독 결제, 저빈도)
     * 하나로 통합하지 않는 이유: 결제 승인 경로 장애가 빌링키 발급 같은 무관한 기능까지
     * 회로를 열어버리는 것을 막기 위함(영향 범위 분리).
     *
     * TimeLimiter를 명시적으로 설정한다 — 원래는 "Feign read-timeout이 이미 동기적으로
     * 시간 제한을 걸어주니 TimeLimiter는 불필요하다"고 판단해서 뺐었다. 그런데 Spring
     * Cloud Circuit Breaker의 Resilience4j 구현체는 TimeLimiter를 커스터마이징하지
     * 않아도 기본값(TimeLimiterConfig.ofDefaults(), timeoutDuration=1초)을 항상
     * 적용한다 — "TimeLimiter를 안 쓴다"는 선택지 자체가 없다. WireMock(응답 100ms
     * 미만)으로 하는 자동화 테스트에서는 이 1초 제한에 걸릴 일이 없어서 안 드러났는데,
     * 실제 Toss 서버로 나가는 요청이 1초를 넘기면서 Feign read-timeout(3초)보다 먼저
     * 끊겨버리는 게 실측(로컬 프로파일, 실제 웹훅 수신 검증 중)으로 발견됐다. Feign
     * read-timeout 값(spring.cloud.openfeign.client.config.tossPaymentsClient.read-timeout)보다
     * 넉넉히 긴 시간으로 맞춰서,
     * 실제로 시간을 끊는 주체가 항상 Feign이 되도록(원래 의도한 동작) 되돌린다.
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> tossCircuitBreakerCustomizer() {
        CircuitBreakerConfig sharedCircuitBreakerConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(waitDurationInOpenStateMs))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        TimeLimiterConfig sharedTimeLimiterConfig = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(tossReadTimeoutMs + TIME_LIMITER_BUFFER_MS))
                .build();

        return factory -> factory.configure(
                builder -> builder
                        .circuitBreakerConfig(sharedCircuitBreakerConfig)
                        .timeLimiterConfig(sharedTimeLimiterConfig),
                "toss-payment", "toss-billing");
    }
}
