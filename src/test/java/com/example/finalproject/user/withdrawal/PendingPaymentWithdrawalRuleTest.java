package com.example.finalproject.user.withdrawal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.withdrawal.rule.PendingPaymentWithdrawalRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingPaymentWithdrawalRuleTest {

    private static final List<PaymentStatus> UNRESOLVED_STATUSES = List.of(
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.RECONCILIATION_REQUIRED,
            PaymentStatus.REFUND_REQUESTED
    );

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private User user;

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"PENDING", "REVERSAL_PENDING", "RECONCILIATION_REQUIRED", "REFUND_REQUESTED"})
    @DisplayName("결과가 미확정인 결제가 있으면 탈퇴를 막는다")
    void blocksWithdrawalWhenPaymentUnresolved(PaymentStatus status) {
        PendingPaymentWithdrawalRule rule = new PendingPaymentWithdrawalRule(paymentRepository);
        when(user.getId()).thenReturn(1L);
        when(paymentRepository.countByOrder_UserIdAndPaymentStatusIn(
                eq(1L), argThat(statuses -> statuses.equals(UNRESOLVED_STATUSES) && statuses.contains(status))
        )).thenReturn(1L);

        var result = rule.validate(user);

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().getCode()).isEqualTo(PendingPaymentWithdrawalRule.CODE);
        verify(paymentRepository).countByOrder_UserIdAndPaymentStatusIn(
                eq(1L), argThat(statuses -> statuses.equals(UNRESOLVED_STATUSES) && statuses.contains(status))
        );
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"APPROVED", "FAILED", "REFUNDED"})
    @DisplayName("종결된 결제만 있으면 탈퇴를 막지 않는다")
    void allowsWithdrawalWhenPaymentResolved(PaymentStatus status) {
        PendingPaymentWithdrawalRule rule = new PendingPaymentWithdrawalRule(paymentRepository);
        when(user.getId()).thenReturn(1L);

        assertThat(rule.validate(user)).isEmpty();
        verify(paymentRepository).countByOrder_UserIdAndPaymentStatusIn(
                eq(1L), argThat(statuses -> statuses.equals(UNRESOLVED_STATUSES) && !statuses.contains(status))
        );
    }
}
