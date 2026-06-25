package com.example.finalproject.subscription.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.example.finalproject.subscription.enums.SubscriptionStatus;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SubscriptionTest {

    private Subscription activeSubscription() {
        return Subscription.builder()
                .totalAmount(10000)
                .startedAt(LocalDateTime.now())
                .status(SubscriptionStatus.ACTIVE)
                .build();
    }

    @Test
    void markPaymentFailed_incrementsFailCount_andSetsNextRetryAtOneDayLater() {
        Subscription subscription = activeSubscription();
        LocalDateTime before = LocalDateTime.now();

        subscription.markPaymentFailed();

        assertThat(subscription.getFailCount()).isEqualTo(1);
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PAYMENT_FAILED);
        assertThat(subscription.getNextRetryAt()).isAfter(before.plusHours(23));
    }

    @Test
    void markPaymentFailed_calledTwice_incrementsFailCountEachTime() {
        Subscription subscription = activeSubscription();

        subscription.markPaymentFailed();
        subscription.markPaymentFailed();

        assertThat(subscription.getFailCount()).isEqualTo(2);
    }

    @Test
    void resetFailCount_setsFailCountToZero_andClearsNextRetryAt() {
        Subscription subscription = activeSubscription();
        subscription.markPaymentFailed();

        subscription.resetFailCount();

        assertThat(subscription.getFailCount()).isEqualTo(0);
        assertThat(subscription.getNextRetryAt()).isNull();
    }

    @Test
    void isRetryExhausted_whenFailCountReachesMax_returnsTrue() {
        Subscription subscription = activeSubscription();
        subscription.markPaymentFailed();
        subscription.markPaymentFailed();
        subscription.markPaymentFailed();

        assertThat(subscription.isRetryExhausted(3)).isTrue();
    }

    @Test
    void isRetryExhausted_whenFailCountBelowMax_returnsFalse() {
        Subscription subscription = activeSubscription();
        subscription.markPaymentFailed();

        assertThat(subscription.isRetryExhausted(3)).isFalse();
    }

    @Test
    void requestCancellation_fromPaymentFailed_succeeds() {
        Subscription subscription = activeSubscription();
        subscription.markPaymentFailed();

        assertThatCode(() -> subscription.requestCancellation("결제 실패로 인한 해지"))
                .doesNotThrowAnyException();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLATION_PENDING);
    }
}
