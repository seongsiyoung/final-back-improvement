package com.example.finalproject.payment.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.domain.SubscriptionPayment;
import com.example.finalproject.payment.service.SubscriptionBillingService;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;

class SubscriptionRecurringProcessorTest {

    private final SubscriptionRepository subscriptionRepository = mock(SubscriptionRepository.class);
    private final SubscriptionBillingService subscriptionBillingService = mock(SubscriptionBillingService.class);
    private final SubscriptionRecurringCommandService commandService =
            mock(SubscriptionRecurringCommandService.class);

    private final SubscriptionRecurringProcessor processor = new SubscriptionRecurringProcessor(
            subscriptionRepository, subscriptionBillingService, commandService);

    @Test
    void processSingleSubscription_onSuccess_advancesSchedule_withoutMarkingFailed() {
        when(subscriptionRepository.existsById(1L)).thenReturn(true);
        when(subscriptionBillingService.chargeMonthlyFee(1L)).thenReturn(mock(SubscriptionPayment.class));

        processor.processSingleSubscription(1L);

        verify(commandService).advanceAfterSuccessfulCharge(1L);
        verify(commandService, org.mockito.Mockito.never()).markPaymentFailed(1L);
    }

    @Test
    void processSingleSubscription_onChargeFailure_marksPaymentFailedAndRethrows() {
        when(subscriptionRepository.existsById(1L)).thenReturn(true);
        when(subscriptionBillingService.chargeMonthlyFee(1L)).thenThrow(new RuntimeException("PG 승인 실패"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> processor.processSingleSubscription(1L));

        verify(commandService).markPaymentFailed(1L);
        verify(commandService, org.mockito.Mockito.never()).advanceAfterSuccessfulCharge(1L);
    }

    @Test
    void processSingleSubscription_onPostProcessingFailure_doesNotMarkPaymentFailed() {
        when(subscriptionRepository.existsById(1L)).thenReturn(true);
        when(subscriptionBillingService.chargeMonthlyFee(1L)).thenReturn(mock(SubscriptionPayment.class));
        org.mockito.Mockito.doThrow(new RuntimeException("배송 일정 생성 실패"))
                .when(commandService).advanceAfterSuccessfulCharge(1L);

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> processor.processSingleSubscription(1L));

        // 결제는 이미 성공했으므로, 후처리 실패를 결제 실패로 잘못 기록하면 안 된다.
        verify(commandService, org.mockito.Mockito.never()).markPaymentFailed(1L);
    }
}
