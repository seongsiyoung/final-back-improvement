package com.example.finalproject.payment.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

class ReconciliationTargetQueryTest extends IntegrationTestSupport {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;

    @Test
    @DisplayName("재조정 대상은 오래된 순으로 상한만큼만 가져온다")
    void findReconciliationTargets_isOrderedAndLimited() {
        Long oldest = seedStuckPayment(PaymentStatus.PENDING, 30);
        Long middle = seedStuckPayment(PaymentStatus.PENDING, 20);
        Long newest = seedStuckPayment(PaymentStatus.PENDING, 10);

        List<Payment> limitedTargets = paymentRepository.findReconciliationTargets(
                List.of(PaymentStatus.PENDING),
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, 2));

        assertThat(limitedTargets).hasSize(2);
        assertThat(limitedTargets).extracting(Payment::getUpdatedAt).isSorted();

        List<Long> seededIds = List.of(oldest, middle, newest);
        List<Long> seededTargetIds = paymentRepository.findReconciliationTargets(
                        List.of(PaymentStatus.PENDING),
                        LocalDateTime.now().minusMinutes(5),
                        PageRequest.of(0, 100))
                .stream()
                .map(Payment::getId)
                .filter(seededIds::contains)
                .toList();

        assertThat(seededTargetIds).containsExactly(oldest, middle, newest);
    }

    @Test
    @DisplayName("최근에 바뀐 건은 대상이 아니다")
    void findReconciliationTargets_excludesRecent() {
        Long recent = seedStuckPayment(PaymentStatus.PENDING, 1);

        List<Payment> targets = paymentRepository.findReconciliationTargets(
                List.of(PaymentStatus.PENDING),
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, 100));

        assertThat(targets).extracting(Payment::getId).doesNotContain(recent);
    }

    @Test
    @DisplayName("여러 상태를 한 번에 뽑는다")
    void findReconciliationTargets_acceptsMultipleStatuses() {
        Long pending = seedStuckPayment(PaymentStatus.PENDING, 30);
        Long reversal = seedStuckPayment(PaymentStatus.REVERSAL_PENDING, 30);

        List<Payment> targets = paymentRepository.findReconciliationTargets(
                List.of(PaymentStatus.PENDING, PaymentStatus.REVERSAL_PENDING),
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, 100));

        assertThat(targets).extracting(Payment::getId).contains(pending, reversal);
    }

    private Long seedStuckPayment(PaymentStatus status, int minutesAgo) {
        return refundScenarioSeeder.stuckPayment(
                "reconciliation-target-" + System.nanoTime() + "@test.com", status, minutesAgo);
    }
}
