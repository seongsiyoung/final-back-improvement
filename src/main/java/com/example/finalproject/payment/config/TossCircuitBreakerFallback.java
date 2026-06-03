package com.example.finalproject.payment.config;

/**
 * CircuitBreakerFactory.create(name).run(supplier, fallback)의 fallback 계약을 처리하는
 * 공용 정책. RuntimeException은 그대로 다시 던지고, 그 외(TimeLimiter의 checked
 * TimeoutException 등)는 RuntimeException으로 감싸 던져 호출부의 기존 catch(RuntimeException)
 * 블록이 그대로 받을 수 있게 한다. Toss 호출부(PaymentService, TossPaymentGateway,
 * BillingService, SubscriptionBillingService) 전체가 이 하나의 정책을 공유한다.
 */
public final class TossCircuitBreakerFallback {

    private TossCircuitBreakerFallback() {
    }

    public static <T> T rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException re) {
            throw re;
        }
        throw new RuntimeException(throwable);
    }
}
