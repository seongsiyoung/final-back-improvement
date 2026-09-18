package com.example.finalproject.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.global.response.ApiResponse;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

    @ParameterizedTest
    @MethodSource("paymentOutcomeErrorCodes")
    void handleBusinessException_keepsPaymentOutcomeCodeInResponse(ErrorCode errorCode) {
        ResponseEntity<ApiResponse<Void>> response = exceptionHandler
                .handleBusinessException(new BusinessException(errorCode));

        assertThat(response.getStatusCode()).isEqualTo(errorCode.getStatus());
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError().getCode()).isEqualTo(errorCode.getCode());
        assertThat(response.getBody().getError().getMessage()).isEqualTo(errorCode.getMessage());
    }

    private static Stream<Arguments> paymentOutcomeErrorCodes() {
        return Stream.of(
                Arguments.of(ErrorCode.PAYMENT_REJECTED),
                Arguments.of(ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE),
                Arguments.of(ErrorCode.PAYMENT_RESULT_PENDING)
        );
    }
}
