package com.example.finalproject.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.finalproject.admin.dto.reconciliation.AdminReconciliationItemResponse;
import com.example.finalproject.admin.service.AdminReconciliationService;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.scheduler.RefundReconciliationScheduler;
import com.example.finalproject.payment.service.AdminRefundCommandService;
import com.example.finalproject.payment.service.PaymentCommandService;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.testsupport.RefundScenarioSeeder;
import com.example.finalproject.user.domain.Role;
import com.example.finalproject.user.domain.UserRole;
import com.example.finalproject.user.repository.RoleRepository;
import com.example.finalproject.user.repository.UserRoleRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 실패 시점부터 복구 완료까지를 한 줄로 잇는 시나리오 테스트.
 *
 * <p>각 Task 의 단위 테스트는 자기 조각만 검증한다. 이 테스트는 조각이 이어졌을 때
 * 실제로 도는지, 그리고 최종 상태뿐 아니라 <b>부수 효과</b>(주문 전이·재고 복구)까지
 * 일어나는지를 본다. 상태만 보면 "상태만 바뀌고 아무 일도 안 일어난 것"을 통과시킨다.
 */
class RecoveryScenarioTest extends IntegrationTestSupport {

    @Autowired
    private RefundReconciliationScheduler refundReconciliationScheduler;
    @Autowired
    private AdminReconciliationService adminReconciliationService;
    @Autowired
    private AdminRefundCommandService adminRefundCommandService;
    @Autowired
    private PaymentCommandService paymentCommandService;
    @Autowired
    private RefundScenarioSeeder refundScenarioSeeder;
    @Autowired
    private LoadTestDataSeeder loadTestDataSeeder;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockBean
    private TossPaymentsClient tossPaymentsClient;
    @MockBean
    private PaymentGateWay paymentGateWay;

    @Test
    @DisplayName("취소 결과를 모른 채 멈춘 환불이 스케줄러를 거쳐 주문 종결과 재고 복구까지 이어진다")
    void refundResultUnknown_recoversThroughToStockRestore() {
        RefundTarget target = refundScenarioSeeder.stuckInPgPending(email());
        refundScenarioSeeder.hideAllReconciliationTargets();
        refundScenarioSeeder.exposeReconciliationTarget(target);
        List<Integer> stockBefore = stockOf(target.storeOrderId());

        // PG 에 취소 흔적이 없다 → 취소를 다시 보낸다
        when(tossPaymentsClient.getPaymentByOrderId(anyString()))
                .thenReturn(response(target.amount(), target.amount()));
        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new CancelResult(target.amount()));

        refundReconciliationScheduler.reconcileStuckRefunds();

        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.APPROVED);
        assertThat(storeOrderStatusOf(target))
                .as("환불 확정에서 멈추면 주문은 취소 요청인 채로 남는다")
                .isEqualTo(StoreOrderStatus.CANCELLED);
        assertThat(stockOf(target.storeOrderId()))
                .as("복구의 실질은 팔 수 있게 돌아온 재고다")
                .isNotEmpty()
                .isEqualTo(stockBefore.stream().map(stock -> stock + 1).toList());
    }

    @Test
    @DisplayName("같은 주문의 다른 매장이 환불돼도 환불된 적 없는 매장 주문은 복구하지 않는다")
    void multiStoreOrder_doesNotRecoverUnrefundedStoreOrder() {
        RefundScenarioSeeder.MultiStoreScenario scenario = refundScenarioSeeder.twoStoresOneOrder(email());
        RefundTarget storeA = scenario.storeA();
        RefundTarget storeB = scenario.storeB();

        // 매장 A 를 취소하고 환불까지 확정한다 → 결제가 PARTIAL_REFUNDED 가 된다
        refundScenarioSeeder.requestCancelOn(storeA);
        paymentCommandService.startRefund(storeA);
        paymentCommandService.markPgApproved(storeA.storeOrderId());
        paymentCommandService.applyRefund(
                storeA.orderId(), storeA.storeOrderId(), storeA.amount(), storeA.reason(), storeA.amount());

        // 매장 B 도 취소를 요청했지만 결제가 이미 환불 진행 중이라 환불 행이 만들어지지 않는다
        refundScenarioSeeder.requestCancelOn(storeB);
        assertThat(paymentStatusOf(storeB))
                .as("결제는 주문 단위라 A 의 환불로 이미 PARTIAL_REFUNDED 다")
                .isEqualTo(PaymentStatus.PARTIAL_REFUNDED);
        assertThat(paymentRefundRepository.findActiveByStoreOrderId(storeB.storeOrderId()))
                .as("B 에는 환불 이력이 없다")
                .isEmpty();

        refundScenarioSeeder.hideAllReconciliationTargets();
        refundScenarioSeeder.exposeReconciliationTarget(storeB);
        List<Integer> stockBefore = stockOf(storeB.storeOrderId());

        refundReconciliationScheduler.recoverLostRefundCompletions();

        assertThat(storeOrderStatusOf(storeB))
                .as("돈이 돌아간 적 없는 매장 주문을 취소로 확정하면 조용한 금전 불일치가 된다")
                .isEqualTo(StoreOrderStatus.CANCEL_REQUESTED);
        assertThat(stockOf(storeB.storeOrderId())).isEqualTo(stockBefore);
    }

    @Test
    @DisplayName("자동 복구가 포기한 환불이 관리자 목록에 뜨고, 해제하면 그 주문의 환불이 다시 열린다")
    void reconciliationRequired_surfacesToAdminAndResolves() {
        RefundTarget target = refundScenarioSeeder.refundStuckInReconciliationRequired(email());

        List<AdminReconciliationItemResponse> actionRequired =
                adminReconciliationService.getActionRequired(adminEmail(), PageRequest.of(0, 200))
                        .refunds().getContent();
        assertThat(actionRequired)
                .as("자동으로 못 푸는 건은 사람이 볼 목록에 떠야 한다")
                .anySatisfy(item -> assertThat(item.id()).isEqualTo(activeRefundIdOf(target)));

        adminRefundCommandService.resolveReconciliation(
                activeRefundIdOf(target), ReconciliationOutcome.NOT_REFUNDED, null);

        assertThat(latestRefundStatus(target)).isEqualTo(RefundStatus.PG_REJECTED);
        assertThat(paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()))
                .as("활성 건이 남으면 이 주문의 환불이 계속 막힌다")
                .isEmpty();
        assertThatCode(() -> paymentCommandService.startRefund(target))
                .as("해제의 목적은 막힌 주문을 다시 여는 것이다")
                .doesNotThrowAnyException();
    }

    private String adminEmail() {
        String email = "recovery-admin-" + System.nanoTime() + "@test.com";
        var user = loadTestDataSeeder.seedUserWithAddress(email, "admin1234!");
        Role role = roleRepository.findByRoleName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().roleName("ADMIN").build()));
        userRoleRepository.save(UserRole.builder().user(user).role(role).build());
        return email;
    }

    private Long activeRefundIdOf(RefundTarget target) {
        return paymentRefundRepository.findActiveByStoreOrderId(target.storeOrderId()).orElseThrow().getId();
    }

    private RefundStatus latestRefundStatus(RefundTarget target) {
        return paymentRefundRepository.findByStoreOrderIdOrderByCreatedAtDesc(target.storeOrderId())
                .getFirst().getRefundStatus();
    }

    private PaymentStatus paymentStatusOf(RefundTarget target) {
        return paymentRepository.findByOrder_Id(target.orderId()).orElseThrow().getPaymentStatus();
    }

    private StoreOrderStatus storeOrderStatusOf(RefundTarget target) {
        return storeOrderRepository.findById(target.storeOrderId()).orElseThrow().getStatus();
    }

    private List<Integer> stockOf(Long storeOrderId) {
        return jdbcTemplate.queryForList(
                "select p.stock from order_products op join products p on p.id = op.product_id "
                        + "where op.store_order_id = ? order by op.id",
                Integer.class,
                storeOrderId);
    }

    private TossConfirmResponse response(int total, int balance) {
        TossConfirmResponse response = new TossConfirmResponse();
        ReflectionTestUtils.setField(response, "totalAmount", total);
        ReflectionTestUtils.setField(response, "balanceAmount", balance);
        return response;
    }

    private String email() {
        return "recovery-scenario-" + System.nanoTime() + "@test.com";
    }
}
