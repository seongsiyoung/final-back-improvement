package com.example.finalproject.admin.service.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * toPaymentStatusLabel()의 switch에 default -> throw가 있어 enum 값을 추가해도 컴파일이 통과한다.
 * 누락은 런타임에만 드러나므로 모든 값을 훑어 확인한다.
 */
class AdminPaymentStatusLabelTest {

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("모든 PaymentStatus 값에 관리자 화면 표시 문구가 있다")
    void everyPaymentStatusHasLabel(PaymentStatus status) {
        AdminFinanceService service = newServiceWithoutDependencies();
        Payment payment = paymentWithStatus(status);

        assertThatCode(() ->
                ReflectionTestUtils.invokeMethod(service, "toPaymentStatusLabel", payment))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    @DisplayName("관리자 화면 표시 문구가 비어 있지 않다")
    void labelIsNotBlank(PaymentStatus status) {
        AdminFinanceService service = newServiceWithoutDependencies();
        Payment payment = paymentWithStatus(status);

        String label = ReflectionTestUtils.invokeMethod(service, "toPaymentStatusLabel", payment);

        assertThat(label).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
            "REVERSAL_PENDING, 취소 처리중",
            "RECONCILIATION_REQUIRED, 확인 필요"
    })
    @DisplayName("복구 상태를 처리 주체에 맞는 문구로 표시한다")
    void recoveryStatusHasExpectedLabel(PaymentStatus status, String expectedLabel) {
        AdminFinanceService service = newServiceWithoutDependencies();
        Payment payment = paymentWithStatus(status);

        String label = ReflectionTestUtils.invokeMethod(service, "toPaymentStatusLabel", payment);

        assertThat(label).isEqualTo(expectedLabel);
    }

    private Payment paymentWithStatus(PaymentStatus status) {
        return Payment.builder()
                .paymentStatus(status)
                .amount(10000)
                .pgOrderId("PG-TEST-" + status.name())
                .pgProvider("tosspayments")
                .build();
    }

    private AdminFinanceService newServiceWithoutDependencies() {
        return mock(AdminFinanceService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS));
    }
}
