package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.domain.StoreCategory;
import com.example.finalproject.store.domain.embedded.SettlementAccount;
import com.example.finalproject.store.domain.embedded.StoreAddress;
import com.example.finalproject.store.domain.embedded.SubmittedDocumentInfo;
import com.example.finalproject.store.repository.StoreCategoryRepository;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import com.example.finalproject.user.domain.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

class AdminRefundCommandServiceTest extends IntegrationTestSupport {

    @Autowired
    private AdminRefundCommandService adminRefundCommandService;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreCategoryRepository storeCategoryRepository;

    @Test
    @DisplayName("PG 거절된 환불을 다시 시도하면 종결 이력을 두고 새 시도를 만든다")
    void retry_whenPgRejected_createsNewAttempt() {
        PaymentRefund rejected = createRefundWithStatus(RefundStatus.PG_REJECTED);
        Long storeOrderId = rejected.getStoreOrder().getId();

        adminRefundCommandService.retry(rejected.getId());

        assertThat(paymentRefundRepository.findById(rejected.getId()).orElseThrow().getRefundStatus())
                .as("종결된 금융 이력을 덮어쓰지 않는다")
                .isEqualTo(RefundStatus.PG_REJECTED);
        assertThat(paymentRefundRepository.findActiveByStoreOrderId(storeOrderId))
                .isPresent()
                .get()
                .extracting(PaymentRefund::getRefundStatus)
                .isEqualTo(RefundStatus.REQUESTED);
        assertThat(storeOrderRepository.findById(storeOrderId).orElseThrow().getStatus())
                .isEqualTo(StoreOrderStatus.REFUND_REQUESTED);
    }

    @Test
    void retry_whenRequested_throwsInvalidRefundStatus() {
        PaymentRefund refund = createRefundWithStatus(RefundStatus.REQUESTED);

        assertThatThrownBy(() -> adminRefundCommandService.retry(refund.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REFUND_STATUS));
    }

    @Test
    void retry_whenApproved_throwsInvalidRefundStatus() {
        PaymentRefund refund = createRefundWithStatus(RefundStatus.APPROVED);

        assertThatThrownBy(() -> adminRefundCommandService.retry(refund.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_REFUND_STATUS));
    }

    @Test
    @DisplayName("고객 취소에서 되살아난 PENDING 주문은 환불 재시도 대상이 아니다")
    void retry_whenStoreOrderIsNotDelivered_throws() {
        PaymentRefund refund = createRefundWithStatus(RefundStatus.PG_REJECTED, StoreOrderStatus.PENDING);

        assertThatThrownBy(() -> adminRefundCommandService.retry(refund.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_STORE_ORDER_REFUND_STATUS));
    }

    private PaymentRefund createRefundWithStatus(RefundStatus status) {
        return createRefundWithStatus(status, StoreOrderStatus.DELIVERED);
    }

    private PaymentRefund createRefundWithStatus(RefundStatus status, StoreOrderStatus storeOrderStatus) {
        User owner = seeder.seedUserWithAddress("retry-owner-" + System.nanoTime() + "@test.com", "owner1234!");
        User buyer = seeder.seedUserWithAddress("retry-buyer-" + System.nanoTime() + "@test.com", "buyer1234!");
        StoreCategory storeCategory = storeCategoryRepository.findByCategoryName("마트/슈퍼")
                .orElseGet(() -> storeCategoryRepository.save(StoreCategory.builder().categoryName("마트/슈퍼").build()));

        String suffix = String.valueOf(System.nanoTime());
        String businessNumber = String.format("%012d", Math.floorMod(System.nanoTime(), 1_000_000_000_000L));
        Store store = storeRepository.save(Store.builder()
                .owner(owner)
                .storeCategory(storeCategory)
                .storeName("재시도테스트-" + suffix)
                .phone("02-0000-0000")
                .description("재시도 테스트 전용 스토어")
                .representativeName("재시도오너")
                .representativePhone("01099999999")
                .submittedDocumentInfo(SubmittedDocumentInfo.builder()
                        .businessOwnerName("재시도오너")
                        .businessNumber(businessNumber)
                        .telecomSalesReportNumber("제2026-재시도-" + suffix)
                        .build())
                .address(StoreAddress.builder()
                        .postalCode("06134")
                        .addressLine1("서울시 강남구 테스트로 123")
                        .addressLine2("1층")
                        .location(GeometryUtil.createPoint(127.0276, 37.4979))
                        .build())
                .settlementAccount(SettlementAccount.builder()
                        .bankName("테스트은행")
                        .bankAccount("110-000-000000")
                        .accountHolder("재시도오너")
                        .build())
                .build());
        store.approve();
        storeRepository.save(store);

        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-RETRY-" + suffix)
                .user(buyer)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(2000)
                .totalDeliveryFee(1000)
                .finalPrice(3000)
                .deliveryAddress("서울시 강남구 테헤란로 123")
                .deliveryLocation(GeometryUtil.createPoint(127.0276, 37.4979))
                .orderedAt(LocalDateTime.now())
                .build());
        Payment payment = paymentRepository.save(Payment.builder()
                .order(order)
                .paymentMethod(PaymentMethodType.CARD)
                .amount(3000)
                .paymentStatus(PaymentStatus.PENDING)
                .pgOrderId("PG-RETRY-" + suffix)
                .pgProvider("tosspayments")
                .build());
        payment.approve("retry-payment-key-" + suffix, "pg-tx", null);
        paymentRepository.save(payment);

        StoreOrder storeOrder = storeOrderRepository.save(StoreOrder.builder()
                .order(order).store(store).orderType(OrderType.REGULAR)
                .storeProductPrice(2000).deliveryFee(1000).finalPrice(3000)
                .build());
        ReflectionTestUtils.setField(storeOrder, "status", storeOrderStatus);
        ReflectionTestUtils.setField(storeOrder, "deliveredAt", LocalDateTime.now());
        storeOrderRepository.save(storeOrder);

        return paymentRefundRepository.save(PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(1000)
                .refundReason("고객 변심")
                .refundStatus(status)
                .build());
    }
}
