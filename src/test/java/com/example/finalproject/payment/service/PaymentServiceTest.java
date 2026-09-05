package com.example.finalproject.payment.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.delivery.service.DeliveryFeeService;
import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.dto.request.TossConfirmRequest;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.testsupport.PassThroughCircuitBreakerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PaymentServiceTest {

    private TossPaymentsClient tossPaymentsClient;
    private PaymentConfirmCommandService paymentConfirmCommandService;
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        tossPaymentsClient = mock(TossPaymentsClient.class);
        paymentConfirmCommandService = mock(PaymentConfirmCommandService.class);

        paymentService = new PaymentService(
                mock(UserLoader.class),
                mock(ProductRepository.class),
                mock(OrderRepository.class),
                mock(OrderLineRepository.class),
                mock(PaymentRepository.class),
                mock(DeliveryFeeService.class),
                tossPaymentsClient,
                paymentConfirmCommandService,
                PassThroughCircuitBreakerFactory.create());
    }

    private PostPaymentConfirmRequest confirmRequest(Long paymentId) {
        PostPaymentConfirmRequest request = new PostPaymentConfirmRequest();
        ReflectionTestUtils.setField(request, "paymentId", paymentId);
        ReflectionTestUtils.setField(request, "paymentKey", "pk-1");
        return request;
    }

    @Test
    void confirm_whenCompleteConfirmAndCancelBothFail_preservesOriginalException_andKeepsReversalPending() {
        TossConfirmRequest tossRequest = new TossConfirmRequest("pk-1", "order-1", 15000);
        when(paymentConfirmCommandService.startConfirm(eq("user@test.com"), eq(1L), eq("pk-1")))
                .thenReturn(tossRequest);
        when(tossPaymentsClient.confirm(any(), any())).thenReturn(mock(TossConfirmResponse.class));

        BusinessException stockFailure = new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        when(paymentConfirmCommandService.completeConfirm(eq(1L), eq("pk-1"), any()))
                .thenThrow(stockFailure);

        RuntimeException cancelFailure = new RuntimeException("PG 취소도 실패");
        when(tossPaymentsClient.cancel(eq("pk-1"), any(), any())).thenThrow(cancelFailure);

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> paymentService.confirm("user@test.com", confirmRequest(1L)));

        // 취소 보상 실패가 원래 원인(재고 부족)을 대체하지 않아야 한다.
        org.assertj.core.api.Assertions.assertThat(thrown).isSameAs(stockFailure);
        org.assertj.core.api.Assertions.assertThat(thrown.getSuppressed()).contains(cancelFailure);
        verify(paymentConfirmCommandService).markReversalPending(1L);
        verify(paymentConfirmCommandService, never()).failReversalPending(1L);
    }

    @Test
    void confirm_whenCompleteConfirmFailsAndCancelSucceeds_rethrowsOriginalException() {
        TossConfirmRequest tossRequest = new TossConfirmRequest("pk-1", "order-1", 15000);
        when(paymentConfirmCommandService.startConfirm(eq("user@test.com"), eq(1L), eq("pk-1")))
                .thenReturn(tossRequest);
        when(tossPaymentsClient.confirm(any(), any())).thenReturn(mock(TossConfirmResponse.class));

        BusinessException stockFailure = new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        when(paymentConfirmCommandService.completeConfirm(eq(1L), eq("pk-1"), any()))
                .thenThrow(stockFailure);

        RuntimeException thrown = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> paymentService.confirm("user@test.com", confirmRequest(1L)));

        org.assertj.core.api.Assertions.assertThat(thrown).isSameAs(stockFailure);
        verify(tossPaymentsClient).cancel(eq("pk-1"), any(), any());
        verify(paymentConfirmCommandService).markReversalPending(1L);
        verify(paymentConfirmCommandService).failReversalPending(1L);
    }
}
