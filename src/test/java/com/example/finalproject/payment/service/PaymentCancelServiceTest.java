package com.example.finalproject.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.finalproject.global.exception.custom.BusinessException;
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
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.service.pg.CancelResult;
import com.example.finalproject.payment.service.pg.PaymentGateWay;
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
import feign.Request;
import feign.Request.HttpMethod;
import feign.RequestTemplate;
import feign.RetryableException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

class PaymentCancelServiceTest extends IntegrationTestSupport {

    @Autowired
    private PaymentCancelService paymentCancelService;
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
    @Autowired
    private PaymentRefundRepository paymentRefundRepository;
    @MockBean
    private PaymentGateWay paymentGateWay;

    @Test
    void cancel_whenPgOutcomeIsUnknown_keepsPgPendingForReconciliation() {
        StoreOrder[] storeOrders = createApprovedPaymentWithTwoStoreOrders();
        StoreOrder storeOrder = storeOrders[0];
        storeOrder.requestCancel();
        storeOrderRepository.save(storeOrder);
        Payment payment = paymentRepository.findByOrder_Id(storeOrder.getOrder().getId()).orElseThrow();
        paymentRefundRepository.save(PaymentRefund.builder()
                .payment(payment)
                .storeOrder(storeOrder)
                .refundAmount(1000)
                .refundReason("고객 변심")
                .build());

        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RetryableException(
                        -1, "read timed out", HttpMethod.POST,
                        new SocketTimeoutException("Read timed out"), (Long) null,
                        Request.create(HttpMethod.POST, "/v1/payments/x/cancel", Collections.emptyMap(),
                                new byte[0], StandardCharsets.UTF_8, new RequestTemplate())));

        assertThatThrownBy(() -> paymentCancelService.cancel(new RefundTarget(
                storeOrder.getOrder().getId(), storeOrder.getId(), 1000, "고객 변심")))
                .isInstanceOf(BusinessException.class);

        Payment reloadedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloadedPayment.getPaymentStatus()).isEqualTo(PaymentStatus.REFUND_REQUESTED);

        PaymentRefund reloadedRefund = paymentRefundRepository.findByStoreOrder_Id(storeOrder.getId()).orElseThrow();
        assertThat(reloadedRefund.getRefundStatus()).isEqualTo(RefundStatus.PG_PENDING);
        assertThat(storeOrderRepository.findById(storeOrder.getId()).orElseThrow().getStatus())
                .isEqualTo(com.example.finalproject.order.enums.StoreOrderStatus.CANCEL_REQUESTED);
    }

    @Test
    void differentStoreOrdersOfSamePayment_useDifferentIdempotencyKeys() {
        StoreOrder[] storeOrders = createApprovedPaymentWithTwoStoreOrders();

        when(paymentGateWay.cancel(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(new CancelResult(1000));

        paymentCancelService.cancel(new RefundTarget(
                storeOrders[0].getOrder().getId(), storeOrders[0].getId(), 1000, "재고 부족"));
        paymentCancelService.cancel(new RefundTarget(
                storeOrders[1].getOrder().getId(), storeOrders[1].getId(), 1000, "재고 부족"));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(paymentGateWay, org.mockito.Mockito.times(2))
                .cancel(anyString(), anyInt(), anyString(), keyCaptor.capture());

        assertThat(keyCaptor.getAllValues())
                .hasSize(2)
                .doesNotHaveDuplicates();
    }

    private StoreOrder[] createApprovedPaymentWithTwoStoreOrders() {
        User ownerA = seeder.seedUserWithAddress("cancel-key-owner-a-" + System.nanoTime() + "@test.com", "owner1234!");
        User ownerB = seeder.seedUserWithAddress("cancel-key-owner-b-" + System.nanoTime() + "@test.com", "owner1234!");
        User buyer = seeder.seedUserWithAddress("cancel-key-buyer-" + System.nanoTime() + "@test.com", "buyer1234!");
        StoreCategory storeCategory = storeCategoryRepository.findByCategoryName("마트/슈퍼")
                .orElseGet(() -> storeCategoryRepository.save(StoreCategory.builder().categoryName("마트/슈퍼").build()));

        Store storeA = createApprovedStore(ownerA, storeCategory, "cancel-key-store-a-" + System.nanoTime());
        Store storeB = createApprovedStore(ownerB, storeCategory, "cancel-key-store-b-" + System.nanoTime());
        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-CANCELKEY-" + System.nanoTime())
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
                .pgOrderId("PG-CANCELKEY-" + System.nanoTime())
                .pgProvider("tosspayments")
                .build());
        payment.approve("cancel-key-payment-key-" + System.nanoTime(), "pg-tx", null);
        paymentRepository.save(payment);

        StoreOrder storeOrderA = storeOrderRepository.save(StoreOrder.builder()
                .order(order).store(storeA).orderType(OrderType.REGULAR)
                .storeProductPrice(1000).deliveryFee(500).finalPrice(1500)
                .build());
        StoreOrder storeOrderB = storeOrderRepository.save(StoreOrder.builder()
                .order(order).store(storeB).orderType(OrderType.REGULAR)
                .storeProductPrice(1000).deliveryFee(500).finalPrice(1500)
                .build());
        return new StoreOrder[]{storeOrderA, storeOrderB};
    }

    private Store createApprovedStore(User owner, StoreCategory storeCategory, String suffix) {
        String businessNumber = String.format("%012d", Math.floorMod(System.nanoTime(), 1_000_000_000_000L));
        Store store = storeRepository.save(Store.builder()
                .owner(owner)
                .storeCategory(storeCategory)
                .storeName("취소키테스트-" + suffix)
                .phone("02-0000-0000")
                .description("취소키 테스트 전용 스토어")
                .representativeName("취소키오너")
                .representativePhone("01099999999")
                .submittedDocumentInfo(SubmittedDocumentInfo.builder()
                        .businessOwnerName("취소키오너")
                        .businessNumber(businessNumber)
                        .telecomSalesReportNumber("제2026-취소키-" + suffix)
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
                        .accountHolder("취소키오너")
                        .build())
                .build());
        store.approve();
        return storeRepository.save(store);
    }
}
