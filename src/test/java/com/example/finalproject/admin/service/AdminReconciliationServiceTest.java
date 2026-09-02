package com.example.finalproject.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.admin.dto.reconciliation.AdminActionRequiredListResponse;
import com.example.finalproject.admin.dto.reconciliation.AdminAutoRecoveryWaitingResponse;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.AdminRefundCommandService;
import com.example.finalproject.payment.service.PaymentCommandService;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import com.example.finalproject.testsupport.SubscriptionScenarioSeeder;
import com.example.finalproject.user.domain.Role;
import com.example.finalproject.user.domain.UserRole;
import com.example.finalproject.user.repository.RoleRepository;
import com.example.finalproject.user.repository.UserRoleRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

class AdminReconciliationServiceTest extends IntegrationTestSupport {

    @Autowired private AdminReconciliationService adminReconciliationService;
    @Autowired private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired private SubscriptionScenarioSeeder subscriptionScenarioSeeder;
    @Autowired private PaymentRefundRepository paymentRefundRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private LoadTestDataSeeder loadTestDataSeeder;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private AdminRefundCommandService adminRefundCommandService;
    @Autowired private PaymentCommandService paymentCommandService;

    @Test
    @DisplayName("자동 복구 대기 건은 처리 필요 목록에 나오지 않는다")
    void actionRequired_excludesAutomaticRecoveryTargets() {
        var automaticRefund = refundScenarioSeeder.stuckInPgPending(newBuyerEmail());
        var actionRefund = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());

        AdminActionRequiredListResponse response = adminReconciliationService.getActionRequired(
                adminEmail(), PageRequest.of(0, 50));

        assertThat(response.refunds().getContent())
                .extracting(item -> item.id())
                .contains(activeRefundId(actionRefund))
                .doesNotContain(activeRefundId(automaticRefund));
        Long actionRefundPaymentId = paymentRepository.findByOrder_Id(actionRefund.orderId()).orElseThrow().getId();
        assertThat(response.payments().getContent())
                .as("환불 해제 API로 처리해야 하는 결제는 결제 해제 목록에 중복 노출하지 않는다")
                .extracting(item -> item.id())
                .doesNotContain(actionRefundPaymentId);
    }

    @Test
    @DisplayName("자동 복구 대기는 상태별 건수와 가장 오래된 변경 시각을 보여준다")
    void autoRecovery_showsStatusCountsAndOldestUpdatedAt() {
        String adminEmail = adminEmail();
        AdminAutoRecoveryWaitingResponse before = adminReconciliationService.getAutoRecoveryWaiting(
                adminEmail, PageRequest.of(0, 100));
        long pendingBefore = before.summary().byStatus().getOrDefault("PENDING", 0L);
        LocalDateTime targetUpdatedAt = before.summary().oldestUpdatedAt() == null
                ? LocalDateTime.now().minusMinutes(45)
                : before.summary().oldestUpdatedAt().minusMinutes(5);

        subscriptionScenarioSeeder.stuckSubscriptionPayment(
                newBuyerEmail(), PaymentStatus.RECONCILIATION_REQUIRED, 10);
        var automaticPayment = subscriptionScenarioSeeder.stuckSubscriptionPayment(
                newBuyerEmail(), PaymentStatus.PENDING, 10);
        jdbcTemplate.update("update subscription_payments set updated_at = ? where id = ?",
                targetUpdatedAt, automaticPayment.getId());
        LocalDateTime persistedUpdatedAt = jdbcTemplate.queryForObject(
                "select updated_at from subscription_payments where id = ?",
                LocalDateTime.class,
                automaticPayment.getId());

        AdminAutoRecoveryWaitingResponse response = adminReconciliationService.getAutoRecoveryWaiting(
                adminEmail, PageRequest.of(0, 100));

        assertThat(response.summary().byStatus()).containsEntry("PENDING", pendingBefore + 1);
        assertThat(response.summary().totalCount()).isEqualTo(before.summary().totalCount() + 1);
        assertThat(response.summary().oldestUpdatedAt())
                .isEqualTo(persistedUpdatedAt.truncatedTo(ChronoUnit.MICROS));
        assertThat(response.subscriptionPayments().getContent())
                .extracting(item -> item.id())
                .contains(automaticPayment.getId());
    }

    @Test
    @DisplayName("StoreOrder가 만들어지지 않은 확인 필요 결제도 처리 필요 목록에 나온다")
    void actionRequired_listsPaymentWithoutStoreOrder() {
        var scenario = refundScenarioSeeder.readyPayment(newBuyerEmail());
        jdbcTemplate.update("update payments set payment_status = ? where id = ?",
                PaymentStatus.RECONCILIATION_REQUIRED.name(), scenario.paymentId());

        AdminActionRequiredListResponse response = adminReconciliationService.getActionRequired(
                adminEmail(), PageRequest.of(0, 100));

        assertThat(response.payments().getContent())
                .filteredOn(item -> item.id().equals(scenario.paymentId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.type()).isEqualTo("PAYMENT");
                    assertThat(item.pgOrderId()).isNotBlank();
                    assertThat(item.orderNumber()).isNotBlank();
                    assertThat(item.allowedOutcomes()).containsExactly("NOT_CHARGED", "REFUNDED");
                    assertThat(item.requiresAmount()).isTrue();
                });
    }

    @Test
    @DisplayName("요청 상태만 남은 주문은 결제 상태와 함께 별도 목록에 나온다")
    void actionRequired_listsDanglingRequestWithBothStates() {
        RefundTarget target = refundScenarioSeeder.cancelRequested(newBuyerEmail());

        AdminActionRequiredListResponse response = adminReconciliationService.getActionRequired(
                adminEmail(), PageRequest.of(0, 100));

        assertThat(response.danglingRequests().getContent())
                .filteredOn(item -> item.id().equals(target.storeOrderId()))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.type()).isEqualTo("DANGLING_REQUEST");
                    assertThat(item.storeOrderStatus()).isEqualTo("CANCEL_REQUESTED");
                    assertThat(item.paymentStatus()).isEqualTo("APPROVED");
                    assertThat(item.amount()).isEqualTo(target.amount());
                });
    }

    @Test
    @DisplayName("PG가 거절한 환불은 최신 재시도 가능 건만 재시도 작업으로 보여준다")
    void actionRequired_listsOnlyLatestRetryablePgRejectedRefund() {
        RefundTarget retryable = refundScenarioSeeder.refundStuckInReconciliationRequired(newBuyerEmail());
        Long firstRejectedId = activeRefundId(retryable);
        adminRefundCommandService.resolveReconciliation(
                firstRejectedId, ReconciliationOutcome.NOT_REFUNDED, null);
        RefundTarget notRetryable = refundScenarioSeeder.withClosedRefundHistory(newBuyerEmail());
        Long notRetryableId = paymentRefundRepository
                .findByStoreOrderIdOrderByCreatedAtDesc(notRetryable.storeOrderId())
                .getFirst()
                .getId();

        AdminActionRequiredListResponse beforeRetry = adminReconciliationService.getActionRequired(
                adminEmail(), PageRequest.of(0, 100));

        assertThat(beforeRetry.refunds().getContent())
                .filteredOn(item -> item.id().equals(firstRejectedId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.actionType()).isEqualTo("RETRY");
                    assertThat(item.allowedOutcomes()).isEmpty();
                    assertThat(item.requiresAmount()).isFalse();
                });
        assertThat(beforeRetry.refunds().getContent())
                .extracting(item -> item.id())
                .doesNotContain(notRetryableId);

        adminRefundCommandService.retry(firstRejectedId);
        Long latestRejectedId = activeRefundId(retryable);
        jdbcTemplate.update("update payment_refunds set refund_status = ? where id = ?",
                RefundStatus.PG_REJECTED.name(), latestRejectedId);
        jdbcTemplate.update("update store_orders set status = ? where id = ?",
                "DELIVERED", retryable.storeOrderId());

        AdminActionRequiredListResponse afterRetry = adminReconciliationService.getActionRequired(
                adminEmail(), PageRequest.of(0, 100));

        assertThat(afterRetry.refunds().getContent())
                .extracting(item -> item.id())
                .contains(latestRejectedId)
                .doesNotContain(firstRejectedId);
    }

    @Test
    @DisplayName("거절 주문의 확인 필요 결제는 불완전한 결제 해제 API로 연결하지 않는다")
    void actionRequired_storeRejectPaymentRequiresInvestigation() {
        RefundTarget target = refundScenarioSeeder.rejectRequested(newBuyerEmail());
        paymentCommandService.startRefund(target);
        paymentCommandService.handleCancelRejection(target);
        Long paymentId = paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getId();

        AdminActionRequiredListResponse response = adminReconciliationService.getActionRequired(
                adminEmail(), PageRequest.of(0, 100));

        assertThat(response.payments().getContent())
                .filteredOn(item -> item.id().equals(paymentId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.actionType()).isEqualTo("INVESTIGATE");
                    assertThat(item.allowedOutcomes()).isEmpty();
                    assertThat(item.requiresAmount()).isFalse();
                });
    }

    @Test
    @DisplayName("관리자 역할이 없는 사용자는 재조정 목록을 조회할 수 없다")
    void actionRequired_rejectsNonAdmin() {
        String customerEmail = loadTestDataSeeder
                .seedUserWithAddress(newBuyerEmail(), "buyer1234!")
                .getEmail();

        assertThatThrownBy(() -> adminReconciliationService.getActionRequired(
                customerEmail, PageRequest.of(0, 10)))
                .isInstanceOf(BusinessException.class);
    }

    private String adminEmail() {
        String email = "admin-reconciliation-" + System.nanoTime() + "@test.com";
        var user = loadTestDataSeeder.seedUserWithAddress(email, "admin1234!");
        Role role = roleRepository.findByRoleName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().roleName("ADMIN").build()));
        userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        return email;
    }

    private String newBuyerEmail() {
        return "dashboard-buyer-" + System.nanoTime() + "@test.com";
    }

    private Long activeRefundId(RefundTarget target) {
        return paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow().getId();
    }
}
