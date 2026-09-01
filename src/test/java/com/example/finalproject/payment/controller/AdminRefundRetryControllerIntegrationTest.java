package com.example.finalproject.payment.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.global.jwt.JwtTokenProvider;
import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentRefund;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.order.enums.StoreOrderStatus;
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
import com.example.finalproject.user.domain.Role;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.domain.UserRole;
import com.example.finalproject.user.repository.RoleRepository;
import com.example.finalproject.user.repository.UserRoleRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AdminRefundRetryControllerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private LoadTestDataSeeder seeder;
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private StoreOrderRepository storeOrderRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private StoreCategoryRepository storeCategoryRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;

    @Test
    void retry_whenPgRejectedByAdmin_returns200AndRevertsToRequested() {
        PaymentRefund refund = createPgRejectedRefund();
        User admin = createAdmin();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(admin, List.of("ADMIN")));
        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/admin/refunds/{refundId}/retry", HttpMethod.POST, new HttpEntity<>(headers), Void.class,
                refund.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PaymentRefund reloaded = paymentRefundRepository.findById(refund.getId()).orElseThrow();
        assertThat(reloaded.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    private User createAdmin() {
        User admin = seeder.seedUserWithAddress("refund-admin-" + System.nanoTime() + "@test.com", "admin1234!");
        Role adminRole = roleRepository.findByRoleName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder().roleName("ADMIN").build()));
        userRoleRepository.save(UserRole.builder().user(admin).role(adminRole).build());
        return admin;
    }

    private PaymentRefund createPgRejectedRefund() {
        String suffix = String.valueOf(System.nanoTime());
        User owner = seeder.seedUserWithAddress("refund-owner-" + suffix + "@test.com", "owner1234!");
        User buyer = seeder.seedUserWithAddress("refund-buyer-" + suffix + "@test.com", "buyer1234!");
        StoreCategory storeCategory = storeCategoryRepository.findByCategoryName("마트/슈퍼")
                .orElseGet(() -> storeCategoryRepository.save(StoreCategory.builder().categoryName("마트/슈퍼").build()));
        Store store = storeRepository.save(Store.builder()
                .owner(owner)
                .storeCategory(storeCategory)
                .storeName("환불 재시도 테스트-" + suffix)
                .phone("02-0000-0000")
                .description("환불 재시도 통합 테스트 전용 스토어")
                .representativeName("테스트오너")
                .representativePhone("01099999999")
                .submittedDocumentInfo(SubmittedDocumentInfo.builder()
                        .businessOwnerName("테스트오너")
                        .businessNumber(String.format("%012d", Math.floorMod(System.nanoTime(), 1_000_000_000_000L)))
                        .telecomSalesReportNumber("제2026-환불-" + suffix)
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
                        .accountHolder("테스트오너")
                        .build())
                .build());
        store.approve();
        storeRepository.save(store);

        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-REFUND-" + suffix)
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
                .pgOrderId("PG-REFUND-" + suffix)
                .pgProvider("tosspayments")
                .build());
        payment.approve("refund-payment-key-" + suffix, "pg-transaction", null);
        paymentRepository.save(payment);

        StoreOrder storeOrder = storeOrderRepository.save(StoreOrder.builder()
                .order(order)
                .store(store)
                .orderType(OrderType.REGULAR)
                .storeProductPrice(2000)
                .deliveryFee(1000)
                .finalPrice(3000)
                .build());
        // 관리자 환불 재시도는 환불 요청 상태의 주문에서만 일어난다.
        ReflectionTestUtils.setField(storeOrder, "status", StoreOrderStatus.REFUND_REQUESTED);
        storeOrderRepository.save(storeOrder);

        return paymentRefundRepository.save(PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(1000)
                .refundReason("고객 변심")
                .refundStatus(RefundStatus.PG_REJECTED)
                .build());
    }
}
