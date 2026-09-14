package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.enums.OrderStatus;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.ReconciliationOutcome;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.user.withdrawal.rule.InProgressOrderWithdrawalRule;
import com.example.finalproject.user.domain.User;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;

/** 승인 기록이 있는 결제와 없는 결제의 종결이 서로 다르게 다뤄지는지 고정한다. */
class ApprovedPaymentTerminationTest extends IntegrationTestSupport {

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentConfirmCommandService paymentConfirmCommandService;
    @Autowired private PaymentReconciliationCommandService paymentReconciliationCommandService;
    @Autowired private TerminatedPaymentCleanupService terminatedPaymentCleanupService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private InProgressOrderWithdrawalRule inProgressOrderWithdrawalRule;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private JdbcTemplate jdbcTemplate;

    // --- Task 2: 승인 기록이 있는 결제는 새 주문을 막지 않는다 ---

    @Test
    @DisplayName("승인 뒤 확인 필요가 된 결제는 새 주문을 막지 않는다")
    void approvedThenReconciliationRequired_doesNotBlockNewCheckout() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("approved-block");
        PostPaymentPrepareResponse first = paymentService.prepare(buyer, request(productId, 2));
        approve(buyer, first);
        // 취소 거절이나 환불 반영 실패가 승인 완료 결제도 이 상태로 올린다.
        forceStatus(first.getPaymentId(), PaymentStatus.RECONCILIATION_REQUIRED);

        PostPaymentPrepareResponse second = paymentService.prepare(buyer, request(productId, 1));

        assertThat(statusOf(second)).isEqualTo(PaymentStatus.READY);
        assertThat(statusOf(first))
                .as("돈을 받은 결제를 실패로 뒤집으면 안 된다")
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(reservedOf(productId))
                .as("승인된 2는 이미 실재고로 빠졌다. 새 선점 1만 남는다")
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class,
            names = {"PENDING", "REVERSAL_PENDING", "RECONCILIATION_REQUIRED"})
    @DisplayName("승인 전 미확정 결제는 여전히 새 주문을 막는다")
    void unapprovedUnresolvedPayment_stillBlocksNewCheckout(PaymentStatus status) {
        Long productId = seedProduct(10);
        String buyer = newBuyer("unapproved-block-" + status);
        PostPaymentPrepareResponse first = paymentService.prepare(buyer, request(productId, 2));
        paymentConfirmCommandService.startConfirm(buyer, first.getPaymentId(), "key-" + System.nanoTime());
        forceStatus(first.getPaymentId(), status);

        assertThatThrownBy(() -> paymentService.prepare(buyer, request(productId, 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));
    }

    // --- Task 3: 승인 기록이 있는 결제를 미청구로 종결할 수 없다 ---

    @Test
    @DisplayName("승인 기록이 있는 결제에 미청구 종결을 시도하면 거부된다")
    void resolveAsNotCharged_whenApproved_isRejected() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("not-charged-reject");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        approve(buyer, prepared);
        forceStatus(prepared.getPaymentId(), PaymentStatus.RECONCILIATION_REQUIRED);

        assertThatThrownBy(() -> paymentReconciliationCommandService.resolvePayment(
                prepared.getPaymentId(), ReconciliationOutcome.NOT_CHARGED, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.APPROVED_PAYMENT_CANNOT_BE_NOT_CHARGED));

        assertThat(statusOf(prepared))
                .as("돈을 받고 물건도 나간 주문이 실패로 기록되면 정산이 어긋난다")
                .isEqualTo(PaymentStatus.RECONCILIATION_REQUIRED);
        assertThat(orderStatusOf(prepared)).isEqualTo(OrderStatus.PAID.name());
    }

    @Test
    @DisplayName("승인 기록이 없는 결제의 미청구 종결은 그대로 동작한다")
    void resolveAsNotCharged_whenNotApproved_settlesPayment() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("not-charged-ok");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        paymentConfirmCommandService.startConfirm(buyer, prepared.getPaymentId(), "key-" + System.nanoTime());
        paymentConfirmCommandService.markConfirmReconciliationRequired(prepared.getPaymentId());

        paymentReconciliationCommandService.resolvePayment(
                prepared.getPaymentId(), ReconciliationOutcome.NOT_CHARGED, null);

        assertThat(statusOf(prepared)).isEqualTo(PaymentStatus.FAILED);
        assertThat(reservedOf(productId)).isZero();
        assertThat(orderStatusOf(prepared)).isEqualTo(OrderStatus.CANCELLED.name());
    }

    // --- Task 4: 결제가 종결되면 주문도 종결한다 ---

    @Test
    @DisplayName("결제 실패로 종결하면 주문도 종결된다")
    void failPending_cancelsOrder() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("fail-order");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        paymentConfirmCommandService.startConfirm(buyer, prepared.getPaymentId(), "key-" + System.nanoTime());

        paymentConfirmCommandService.failPending(prepared.getPaymentId());

        assertThat(orderStatusOf(prepared)).isEqualTo(OrderStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("만료로 종결하면 주문도 종결된다")
    void expireReadyPayment_cancelsOrder() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("expire-order");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));

        paymentConfirmCommandService.expireReadyPayment(prepared.getPaymentId());

        assertThat(orderStatusOf(prepared)).isEqualTo(OrderStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("새 결제 준비가 옛 READY 건을 대체하면 그 주문도 종결된다")
    void prepareReplacingReady_cancelsOldOrder() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("replace-order");
        PostPaymentPrepareResponse first = paymentService.prepare(buyer, request(productId, 2));

        paymentService.prepare(buyer, request(productId, 1));

        assertThat(orderStatusOf(first)).isEqualTo(OrderStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("주문이 종결되면 진행 중인 주문 규칙이 탈퇴를 막지 않는다")
    void settledOrder_doesNotBlockWithdrawal() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("withdrawal");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        User user = seeder.seedUserWithAddress(buyer, "buyer1234!");

        assertThat(inProgressOrderWithdrawalRule.validate(user))
                .as("결제 준비만 한 주문도 진행 중으로 잡힌다")
                .isPresent();

        paymentConfirmCommandService.expireReadyPayment(prepared.getPaymentId());

        assertThat(inProgressOrderWithdrawalRule.validate(user))
                .as("결제창을 한 번 열었다는 이유로 영영 탈퇴하지 못하면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("승인까지 간 주문은 관리자 해제로도 상태가 바뀌지 않는다")
    void approvedOrder_isNotCancelledByAdminResolve() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("approved-order");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        approve(buyer, prepared);
        forceStatus(prepared.getPaymentId(), PaymentStatus.RECONCILIATION_REQUIRED);

        assertThatCode(() -> paymentReconciliationCommandService.resolvePayment(
                prepared.getPaymentId(), ReconciliationOutcome.REFUNDED, 1000))
                .doesNotThrowAnyException();

        assertThat(orderStatusOf(prepared))
                .as("환불은 환불 경로가 주문 상태를 옮긴다. 여기서 취소로 뒤집으면 안 된다")
                .isEqualTo(OrderStatus.PAID.name());
        assertThat(stockOf(productId))
                .as("승인으로 확정된 재고를 되돌리면 안 된다")
                .isEqualTo(8);
    }

    @Test
    @DisplayName("보상 취소로 종결해도 주문이 종결된다")
    void failReversalPending_cancelsOrder() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("reversal-order");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        paymentConfirmCommandService.startConfirm(buyer, prepared.getPaymentId(), "key-" + System.nanoTime());
        paymentConfirmCommandService.markReversalPending(prepared.getPaymentId());

        paymentConfirmCommandService.failReversalPending(prepared.getPaymentId());

        assertThat(orderStatusOf(prepared)).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(reservedOf(productId)).isZero();
    }

    @Test
    @DisplayName("승인되지 않은 결제를 환불로 종결하면 선점과 주문이 정리된다")
    void resolveAsRefunded_whenNotApproved_cleansUp() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("refunded-unapproved");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        // PG 는 청구했는데 completeConfirm 이 롤백된 경우다. 승인 기록이 없고 선점은 살아 있다.
        paymentConfirmCommandService.startConfirm(buyer, prepared.getPaymentId(), "key-" + System.nanoTime());
        paymentConfirmCommandService.markReversalPending(prepared.getPaymentId());
        paymentConfirmCommandService.markConfirmReconciliationRequired(prepared.getPaymentId());

        paymentReconciliationCommandService.resolvePayment(
                prepared.getPaymentId(), ReconciliationOutcome.REFUNDED, prepared.getAmount());

        assertThat(reservedOf(productId))
                .as("승인 기록이 없으므로 선점이 아직 살아 있다. 반납해야 한다")
                .isZero();
        assertThat(orderStatusOf(prepared)).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(stockOf(productId)).as("팔린 적이 없다").isEqualTo(10);
    }

    @Test
    @DisplayName("정리는 호출자 트랜잭션 없이는 실행되지 않는다")
    void cleanUp_requiresCallerTransaction() {
        Long productId = seedProduct(10);
        String buyer = newBuyer("mandatory");
        PostPaymentPrepareResponse prepared = paymentService.prepare(buyer, request(productId, 2));
        Payment payment = paymentRepository.findById(prepared.getPaymentId()).orElseThrow();

        assertThatThrownBy(() -> terminatedPaymentCleanupService.cleanUp(payment))
                .as("자체 트랜잭션을 열면 종결과 정리가 따로 커밋돼 어긋난 구간이 생긴다")
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(reservedOf(productId)).isEqualTo(2);
    }

    private void approve(String email, PostPaymentPrepareResponse prepared) {
        String paymentKey = "approved-term-" + System.nanoTime();
        paymentConfirmCommandService.startConfirm(email, prepared.getPaymentId(), paymentKey);
        TossConfirmResponse pg = new TossConfirmResponse();
        ReflectionTestUtils.setField(pg, "paymentKey", paymentKey);
        ReflectionTestUtils.setField(pg, "totalAmount", prepared.getAmount());
        paymentConfirmCommandService.completeConfirm(prepared.getPaymentId(), paymentKey, pg);
    }

    private void forceStatus(Long paymentId, PaymentStatus status) {
        jdbcTemplate.update("update payments set payment_status = ? where id = ?", status.name(), paymentId);
    }

    private PostPaymentPrepareRequest request(Long productId, int quantity) {
        PostPaymentPrepareRequest request = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(request, "productQuantities", Map.of(productId, quantity));
        ReflectionTestUtils.setField(request, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(request, "deliveryAddress", "서울시 강남구 테헤란로 123");
        return request;
    }

    private Long seedProduct(int stock) {
        Store store = seeder.seedStoreWithProducts(
                "approved-term-" + System.nanoTime() + "@test.com", 1, stock);
        return productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();
    }

    private String newBuyer(String prefix) {
        String email = "approved-term-" + prefix + "-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "buyer1234!");
        return email;
    }

    private PaymentStatus statusOf(PostPaymentPrepareResponse prepared) {
        return paymentRepository.findById(prepared.getPaymentId()).orElseThrow().getPaymentStatus();
    }

    private String orderStatusOf(PostPaymentPrepareResponse prepared) {
        return jdbcTemplate.queryForObject(
                "select o.status from orders o join payments p on p.order_id = o.id where p.id = ?",
                String.class, prepared.getPaymentId());
    }

    private int stockOf(Long productId) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private int reservedOf(Long productId) {
        return jdbcTemplate.queryForObject("select reserved from products where id = ?", Integer.class, productId);
    }
}
