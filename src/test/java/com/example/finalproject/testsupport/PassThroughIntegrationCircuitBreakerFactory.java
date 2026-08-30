package com.example.finalproject.testsupport;

import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.cloud.client.circuitbreaker.ConfigBuilder;

/**
 * 통합 테스트에서 Spring 빈으로 등록해 서킷브레이커를 실제로 우회하는 pass-through 구현체.
 *
 * <p>{@link PassThroughCircuitBreakerFactory}(Mockito 기반)는 순수 단위 테스트에서 서비스
 * 생성자에 직접 주입하는 용도로 만들어졌다 — Mockito mock을 Spring
 * {@code ApplicationContext}에 빈으로 올려서 오래 실행되는 통합 테스트(장시간 동시 호출,
 * 여러 스레드)에서 안정적으로 동작한다는 보장이 없어 여기서는 재사용하지 않는다. 이 클래스는
 * Mockito 없이 {@link CircuitBreakerFactory}를 직접 구현해, supplier를 호출 스레드에서
 * 그대로 동기 실행한다 — 서킷브레이커도, TimeLimiter(별도 executor)도 전혀 개입하지 않는다.
 * Feign 자체의 read-timeout만 그대로 적용된 "타임아웃은 있지만 서킷브레이커는 없는" 상태를
 * 재현하는 게 목적이다.
 */
public final class PassThroughIntegrationCircuitBreakerFactory extends CircuitBreakerFactory<Object, ConfigBuilder<Object>> {

    @Override
    public CircuitBreaker create(String id) {
        return new CircuitBreaker() {
            @Override
            public <T> T run(Supplier<T> toRun, Function<Throwable, T> fallback) {
                try {
                    return toRun.get();
                } catch (Throwable t) {
                    return fallback.apply(t);
                }
            }
        };
    }

    @Override
    protected ConfigBuilder<Object> configBuilder(String id) {
        return Object::new;
    }

    @Override
    public void configureDefault(Function<String, Object> defaultConfiguration) {
        // 통합 테스트에서 config 자체를 조회하지 않으므로 아무 것도 안 해도 된다.
    }
}
