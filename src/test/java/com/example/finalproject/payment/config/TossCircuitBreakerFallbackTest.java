package com.example.finalproject.payment.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class TossCircuitBreakerFallbackTest {

    @Test
    void rethrow_whenRuntimeException_rethrowsSameInstance() {
        RuntimeException original = new IllegalStateException("boom");

        assertThatThrownBy(() -> TossCircuitBreakerFallback.<Void>rethrow(original))
                .isSameAs(original);
    }

    @Test
    void rethrow_whenCheckedException_wrapsInRuntimeException() {
        IOException checked = new IOException("checked failure");

        assertThatThrownBy(() -> TossCircuitBreakerFallback.<Void>rethrow(checked))
                .isInstanceOf(RuntimeException.class)
                .hasCause(checked);
    }
}
