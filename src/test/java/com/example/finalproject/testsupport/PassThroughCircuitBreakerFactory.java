package com.example.finalproject.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

/**
 * Mockito 단위 테스트에서 서킷브레이커 오케스트레이션(호출 순서)만 검증하고 싶을 때 쓰는
 * 가짜 CircuitBreakerFactory. 항상 CLOSED 상태인 것처럼 supplier를 그대로 실행한다 —
 * 서킷브레이커 자체의 open/half-open 전이는 TossCircuitBreakerTest(통합 테스트)에서 검증한다.
 */
public final class PassThroughCircuitBreakerFactory {

    private PassThroughCircuitBreakerFactory() {
    }

    public static CircuitBreakerFactory<?, ?> create() {
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(circuitBreaker.run(any(Supplier.class), any(Function.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return supplier.get();
                });

        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        when(factory.create(anyString())).thenReturn(circuitBreaker);
        return factory;
    }
}
