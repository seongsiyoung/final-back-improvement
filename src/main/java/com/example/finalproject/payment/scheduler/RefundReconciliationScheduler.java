package com.example.finalproject.payment.scheduler;

import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.order.service.RefundCompletionRecoveryService;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.service.RefundReconciliationService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 환불 경로에서 멈춘 두 종류를 집어간다. PG 결과가 미확정인 환불과,
 * 환불은 확정됐는데 주문 후속 처리만 실행되지 못한 건이다.
 *
 * <p>@Transactional 이 없다. 클래스에도 붙이지 않는다. 루프 안에서 PG 를 호출하고,
 * 트랜잭션 경계는 각 복구 단위가 부르는 커맨드 서비스가 갖는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundReconciliationScheduler {

    private static final List<RefundStatus> REFUND_TARGET_STATUSES =
            List.of(RefundStatus.PG_PENDING, RefundStatus.PG_APPROVED);
    private static final List<PaymentStatus> COMPLETED_PAYMENT_STATUSES =
            List.of(PaymentStatus.REFUNDED, PaymentStatus.PARTIAL_REFUNDED);
    private static final List<StoreOrderStatus> PENDING_FOLLOW_UP_STATUSES =
            List.of(StoreOrderStatus.CANCEL_REQUESTED,
                    StoreOrderStatus.REJECT_REQUESTED,
                    StoreOrderStatus.REFUND_REQUESTED);

    private final PaymentRefundRepository paymentRefundRepository;
    private final StoreOrderRepository storeOrderRepository;
    private final RefundReconciliationService refundReconciliationService;
    private final RefundCompletionRecoveryService refundCompletionRecoveryService;

    @Value("${reconciliation.batch-size:100}")
    private int batchSize;
    @Value("${reconciliation.stale-threshold-minutes:5}")
    private long staleThresholdMinutes;

    @Scheduled(fixedDelay = 300_000L)
    public void reconcileStuckRefunds() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);

        for (PaymentRefund refund : paymentRefundRepository.findReconciliationTargets(
                REFUND_TARGET_STATUSES, threshold, PageRequest.of(0, batchSize))) {
            try {
                refundReconciliationService.reconcile(refund);
            } catch (Exception e) {
                // 한 건이 실패해도 나머지는 계속 처리한다. 실패한 건은 상태가 남아 다음 주기에 다시 잡힌다.
                log.error("환불 재조정 실패. refundId={}, status={}",
                        refund.getId(), refund.getRefundStatus(), e);
            }
        }
    }

    @Scheduled(fixedDelay = 300_000L)
    public void recoverLostRefundCompletions() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);

        for (StoreOrder storeOrder : storeOrderRepository.findRefundCompletionLostTargets(
                COMPLETED_PAYMENT_STATUSES, PENDING_FOLLOW_UP_STATUSES, threshold,
                PageRequest.of(0, batchSize))) {
            try {
                refundCompletionRecoveryService.recover(storeOrder);
            } catch (Exception e) {
                log.error("환불 후속 처리 복구 실패. storeOrderId={}", storeOrder.getId(), e);
            }
        }
    }
}
