package com.example.finalproject.payment.service;

import com.example.finalproject.delivery.service.DeliveryFeeService;
import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.payment.client.TossPaymentsClient;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.request.PostPaymentConfirmRequest;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.dto.request.TossCancelRequest;
import com.example.finalproject.payment.dto.request.TossConfirmRequest;
import com.example.finalproject.payment.dto.response.PostPaymentConfirmResponse;
import com.example.finalproject.payment.dto.response.PostPaymentPrepareResponse;
import com.example.finalproject.payment.config.TossCircuitBreakerFallback;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final UserLoader userLoader;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final PaymentRepository paymentRepository;
    private final DeliveryFeeService deliveryFeeService;
    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentConfirmCommandService paymentConfirmCommandService;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;


    @Transactional
    public PostPaymentPrepareResponse prepare(
            String email,
            PostPaymentPrepareRequest request) {

        User user = userLoader.loadUserByUsername(email);

        validateRequest(request);

        List<Product> products = loadAndValidateProducts(request);

        Order order = createOrder(user, request, products);

        // 주문 라인(실제 결제가 진행 전 임시 데이터 저장할 엔티티)
        createOrderLines(order, request, products);

        Payment payment = createPayment(order, request);

        return new PostPaymentPrepareResponse(
                order.getId(),
                payment.getId(),
                payment.getPgOrderId(),
                payment.getAmount()
        );
    }

    public PostPaymentConfirmResponse confirm(
            String email,
            PostPaymentConfirmRequest request) {

        TossConfirmRequest confirmRequest = paymentConfirmCommandService.startConfirm(
                email,
                request.getPaymentId(),
                request.getPaymentKey()
        );

        TossConfirmResponse pg = confirmWithRollback(request.getPaymentId(), confirmRequest);

        return completeConfirmOrCancel(request, confirmRequest, pg);
    }

    private TossConfirmResponse confirmWithRollback(Long paymentId, TossConfirmRequest confirmRequest) {
        try {
            return circuitBreakerFactory.create("toss-payment")
                    .run(() -> tossPaymentsClient.confirm(confirmRequest), TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException e) {
            paymentConfirmCommandService.revertPendingToReady(paymentId);
            throw e;
        }
    }

    private PostPaymentConfirmResponse completeConfirmOrCancel(
            PostPaymentConfirmRequest request,
            TossConfirmRequest confirmRequest,
            TossConfirmResponse pg) {
        try {
            return paymentConfirmCommandService.completeConfirm(
                    request.getPaymentId(),
                    request.getPaymentKey(),
                    pg
            );
        } catch (BusinessException e) {
            cancelApprovedPayment(request.getPaymentId(), request.getPaymentKey(), confirmRequest.getAmount(), e);
            throw e;
        }
    }

    private void cancelApprovedPayment(Long paymentId, String paymentKey, int amount, BusinessException original) {
        try {
            circuitBreakerFactory.create("toss-payment")
                    .run(() -> {
                        tossPaymentsClient.cancel(paymentKey, new TossCancelRequest("재고 부족으로 결제 취소", amount));
                        return null;
                    }, TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException cancelFailure) {
            // 보상 취소 자체가 실패해도 원래 실패 원인(original)을 대체하지 않는다 — 재고 부족 등
            // 원래 원인이 사라지고 취소 실패 예외로 뒤바뀌면 클라이언트가 진짜 원인을 알 수 없다.
            log.error("결제 승인 반영 실패 후 PG 취소 보상도 실패함. paymentId={}, paymentKey={}",
                    paymentId, paymentKey, cancelFailure);
            original.addSuppressed(cancelFailure);
        }
        // 취소 보상이 실패해도 failPending은 반드시 호출한다 — 그렇지 않으면 Payment가
        // PENDING에 영구히 남아 재시도조차 불가능해진다.
        paymentConfirmCommandService.failPending(paymentId);
    }


    private void validateRequest(PostPaymentPrepareRequest request) {
        if (request.getProductQuantities() == null || request.getProductQuantities().isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    private List<Product> loadAndValidateProducts(PostPaymentPrepareRequest request) {

        Map<Long, Integer> quantities = request.getProductQuantities();

        List<Product> products = productRepository.findAllById(quantities.keySet());

        if (products.size() != quantities.size()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        for (Product product : products) {

            int qty = quantities.get(product.getId());

            // 삭제 여부
            if (product.isDeleted()) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }

            // 판매 상태
            if (!Boolean.TRUE.equals(product.getIsActive())) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
            }

            // 재고 충분
            if (product.getStock() < qty) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
            }

            // 가격 검증
            int price = product.getEffectivePrice();
            if (price <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PRICE);
            }
        }

        return products;
    }

    private Order createOrder(
            User user,
            PostPaymentPrepareRequest request,
            List<Product> products) {

        Map<Long, Integer> quantities = request.getProductQuantities();

        int totalProductPrice = 0;
        for (Product product : products) {
            totalProductPrice += product.getEffectivePrice() * quantities.get(product.getId());
        }

        int deliveryFee = deliveryFeeService.calculateTotalDeliveryFee(user.getId(), products);

        int finalPrice = totalProductPrice + deliveryFee;

        if (finalPrice <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ORDER_AMOUNT);
        }

        Address address = user.getAddresses().stream().filter(Address::getIsDefault).findAny()
                .orElseThrow(() -> new BusinessException(ErrorCode.ADDRESS_NOT_FOUND));

        Order order = Order.builder()
                .orderNumber(generateOrderNumber())
                .user(user)
                .orderType(OrderType.REGULAR)
                .totalProductPrice(totalProductPrice)
                .totalDeliveryFee(deliveryFee)
                .finalPrice(finalPrice)
                .deliveryAddress(request.getDeliveryAddress())
                .deliveryRequest(request.getDeliveryRequest())
                .deliveryLocation(address.getLocation())
                .orderedAt(LocalDateTime.now())
                .build();

        return orderRepository.save(order);
    }

    private void createOrderLines(
            Order order,
            PostPaymentPrepareRequest request,
            List<Product> products) {

        Map<Long, Integer> quantities = request.getProductQuantities();

        for (Product product : products) {
            OrderLine line = OrderLine.builder()
                    .order(order)
                    .productId(product.getId())
                    .storeId(product.getStore().getId())
                    .priceSnapshot(product.getEffectivePrice())
                    .productNameSnapshot(product.getProductName())
                    .quantity(quantities.get(product.getId()))
                    .build();

            orderLineRepository.save(line);
        }
    }

    private Payment createPayment(
            Order order,
            PostPaymentPrepareRequest request) {

        Payment payment = Payment.builder()
                .order(order)
                .paymentMethod(request.getPaymentMethod())
                .amount(order.getFinalPrice())
                .paymentStatus(PaymentStatus.READY)
                .pgProvider("tosspayments")
                .pgOrderId(generatePgOrderId(order))
                .build();

        return paymentRepository.save(payment);
    }

    private String generateOrderNumber() {
        return "ORD-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String generatePgOrderId(Order order) {
        return "PG-" + order.getOrderNumber();
    }
}
