package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 환불 경로가 잠근 결제를 다른 요청이 낡은 상태로 덮어쓰지 않는지 고정한다. */
class RefundLockConcurrencyTest extends IntegrationTestSupport {

    @Autowired private PaymentCommandService paymentCommandService;
    @Autowired private RefundScenarioSeeder seeder;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 잠그기 전에 읽으면 먼저 들어온 환불 요청이 안 보인다. 중복 가드를 통과해 같은
     * 매장 주문에 환불 행이 두 개 생기고 PG 취소가 두 번 나간다.
     */
    @Test
    @DisplayName("환불 요청이 겹쳐도 하나만 통과한다")
    void startRefund_whenAnotherRequestHoldsTheLock_isRejected() throws Exception {
        RefundTarget target = seeder.approvedWithPendingStoreOrder(
                "refund-lock-" + System.nanoTime() + "@test.com");

        CountDownLatch refundLocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Throwable secondOutcome;
        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                paymentCommandService.startRefund(target);
                refundLocked.countDown();
                awaitQuietly(release);
            }));

            assertThat(refundLocked.await(10, TimeUnit.SECONDS))
                    .as("첫 환불 요청이 잠금을 잡지 못했다")
                    .isTrue();
            Future<?> second = executor.submit(() -> paymentCommandService.startRefund(target));
            awaitRowLockWaiter(jdbcTemplate);
            release.countDown();

            first.get(10, TimeUnit.SECONDS);
            secondOutcome = catchCauseOf(second);
        } finally {
            executor.shutdownNow();
        }

        assertThat(secondOutcome)
                .as("낡은 APPROVED 를 보면 중복 가드를 그냥 지나간다")
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.ALREADY_PROCESSED_PAYMENT));
    }

    private Throwable catchCauseOf(Future<?> future) throws InterruptedException {
        try {
            future.get(10, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 홀더의 대기 예산은 awaitRowLockWaiter 의 데드라인보다 커야 먼저 커밋하지 않는다. */
    private void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금을 놓아줄 신호가 오지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
