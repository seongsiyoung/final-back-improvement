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
import com.example.finalproject.payment.client.TossIdempotencyKeys;
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
import com.example.finalproject.payment.service.pg.PgCallOutcome;
import com.example.finalproject.payment.service.pg.PgFailureClassifier;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.User;
import feign.FeignException;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;
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

    /** 사용자별 승인 전 미확정 결제 상한. */
    private static final int MAX_UNRESOLVED_PAYMENTS = 3;
    private static final EnumSet<PaymentStatus> REPLACEABLE_PAYMENT_STATUSES = EnumSet.of(
            PaymentStatus.READY
    );
    private static final EnumSet<PaymentStatus> UNRESOLVED_PAYMENT_STATUSES = EnumSet.of(
            PaymentStatus.PENDING,
            PaymentStatus.REVERSAL_PENDING,
            PaymentStatus.RECONCILIATION_REQUIRED
    );
    private static final Set<String> FAILED_PG_STATUSES = Set.of("ABORTED", "CANCELED", "EXPIRED");

    private final UserLoader userLoader;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderLineRepository orderLineRepository;
    private final PaymentRepository paymentRepository;
    private final DeliveryFeeService deliveryFeeService;
    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentConfirmCommandService paymentConfirmCommandService;
    private final CircuitBreakerFactory<?, ?> circuitBreakerFactory;
    private final TerminatedPaymentCleanupService terminatedPaymentCleanupService;


    @Transactional
    public PostPaymentPrepareResponse prepare(
            String email,
            PostPaymentPrepareRequest request) {

        User user = userLoader.loadUserByUsernameWithLock(email);

        List<Payment> replaceable = loadReplaceableReadyPayments(user.getId());

        blockWhenUnresolvedPaymentsReachCap(user.getId());

        validateRequest(request);

        lockCheckoutProducts(replaceable, request);

        replaceable.forEach(payment -> {
            payment.fail();
            terminatedPaymentCleanupService.cleanUp(payment);
        });

        List<Product> products = lockValidateAndReserveProducts(request);

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

    private void blockWhenUnresolvedPaymentsReachCap(Long userId) {
        long unresolved = paymentRepository.countByOrder_UserIdAndPaymentStatusInAndPaidAtIsNull(
                userId, UNRESOLVED_PAYMENT_STATUSES);

        if (unresolved >= MAX_UNRESOLVED_PAYMENTS) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
    }

    private List<Payment> loadReplaceableReadyPayments(Long userId) {
        return paymentRepository.lockByUserIdAndStatuses(
                userId, REPLACEABLE_PAYMENT_STATUSES);
    }

    /** 기존 READY 반납과 신규 선점 대상 상품을 같은 순서로 잠근다. */
    private void lockCheckoutProducts(List<Payment> replaceable, PostPaymentPrepareRequest request) {
        Set<Long> productIds = new TreeSet<>(request.getProductQuantities().keySet());

        for (Payment payment : replaceable) {
            orderLineRepository.findAllByOrderId(payment.getOrder().getId())
                    .forEach(line -> productIds.add(line.getProductId()));
        }

        for (Long productId : productIds) {
            productRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        }
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
            String idempotencyKey = TossIdempotencyKeys.forConfirm(paymentId, confirmRequest.getPaymentKey());
            return circuitBreakerFactory.create("toss-payment")
                    .run(() -> tossPaymentsClient.confirm(confirmRequest, idempotencyKey), TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException e) {
            PgCallOutcome outcome = PgFailureClassifier.classify(e);
            log.error("[PG_CONFIRM_ERROR] paymentId={}, outcome={}, error={}",
                    paymentId, outcome, e.getMessage(), e);
            if (outcome == PgCallOutcome.RESULT_UNKNOWN) {
                PgLookupResult lookup = lookupPaymentOnce(paymentId, confirmRequest.getOrderId());
                if (lookup.outcome() == PgCallOutcome.SUCCESS) {
                    return lookup.response();
                }
                outcome = lookup.outcome();
            }
            if (outcome == PgCallOutcome.NOT_SENT || outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                paymentConfirmCommandService.failPending(paymentId);
            }
            throw new BusinessException(errorCodeFor(outcome), e);
        }
    }

    private PgLookupResult lookupPaymentOnce(Long paymentId, String pgOrderId) {
        TossConfirmResponse pg;
        try {
            pg = circuitBreakerFactory.create("toss-payment")
                    .run(() -> tossPaymentsClient.getPaymentByOrderId(pgOrderId), TossCircuitBreakerFallback::rethrow);
        } catch (FeignException.NotFound e) {
            return new PgLookupResult(PgCallOutcome.EXPLICIT_REJECTION, null);
        } catch (RuntimeException e) {
            log.error("[PG_CONFIRM_LOOKUP_ERROR] paymentId={}, pgOrderId={}, error={}",
                    paymentId, pgOrderId, e.getMessage(), e);
            return new PgLookupResult(PgCallOutcome.RESULT_UNKNOWN, null);
        }

        if (pg == null) {
            return new PgLookupResult(PgCallOutcome.RESULT_UNKNOWN, null);
        }

        String status = pg.getStatus();
        if ("DONE".equals(status)) {
            return new PgLookupResult(PgCallOutcome.SUCCESS, pg);
        }
        if ("PARTIAL_CANCELED".equals(status)) {
            paymentConfirmCommandService.markConfirmReconciliationRequired(paymentId);
        } else if (FAILED_PG_STATUSES.contains(status)) {
            return new PgLookupResult(PgCallOutcome.EXPLICIT_REJECTION, null);
        }
        return new PgLookupResult(PgCallOutcome.RESULT_UNKNOWN, null);
    }

    private ErrorCode errorCodeFor(PgCallOutcome outcome) {
        return switch (outcome) {
            case EXPLICIT_REJECTION -> ErrorCode.PAYMENT_REJECTED;
            case NOT_SENT -> ErrorCode.PAYMENT_TEMPORARILY_UNAVAILABLE;
            case RESULT_UNKNOWN -> ErrorCode.PAYMENT_RESULT_PENDING;
            case SUCCESS -> throw new IllegalStateException("성공한 PG 호출은 예외 응답으로 변환할 수 없습니다.");
        };
    }

    private record PgLookupResult(PgCallOutcome outcome, TossConfirmResponse response) {
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
        paymentConfirmCommandService.markReversalPending(paymentId);
        try {
            String idempotencyKey = TossIdempotencyKeys.forCompensatingCancel(paymentId);
            circuitBreakerFactory.create("toss-payment")
                    .run(() -> {
                        tossPaymentsClient.cancel(paymentKey, new TossCancelRequest("재고 부족으로 결제 취소", amount), idempotencyKey);
                        return null;
                    }, TossCircuitBreakerFallback::rethrow);
        } catch (RuntimeException cancelFailure) {
            PgCallOutcome outcome = PgFailureClassifier.classify(cancelFailure);
            // 보상 취소 자체가 실패해도 원래 실패 원인(original)을 대체하지 않는다 — 재고 부족 등
            // 원래 원인이 사라지고 취소 실패 예외로 뒤바뀌면 클라이언트가 진짜 원인을 알 수 없다.
            log.error("[PG_REVERSAL_ERROR] paymentId={}, paymentKey={}, outcome={}",
                    paymentId, paymentKey, outcome, cancelFailure);
            original.addSuppressed(cancelFailure);
            if (outcome == PgCallOutcome.EXPLICIT_REJECTION) {
                paymentConfirmCommandService.markConfirmReconciliationRequired(paymentId);
            }
            return;
        }
        paymentConfirmCommandService.failReversalPending(paymentId);
    }


    private void validateRequest(PostPaymentPrepareRequest request) {
        if (request.getProductQuantities() == null || request.getProductQuantities().isEmpty()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    private List<Product> lockValidateAndReserveProducts(PostPaymentPrepareRequest request) {

        Map<Long, Integer> quantities = request.getProductQuantities();

        List<Product> products = new ArrayList<>();

        for (Long productId : quantities.keySet().stream().sorted().toList()) {

            Product product = productRepository.findByIdForUpdate(productId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

            int qty = quantities.get(productId);

            // 삭제 여부
            if (product.isDeleted()) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
            }

            // 판매 상태
            if (!Boolean.TRUE.equals(product.getIsActive())) {
                throw new BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE);
            }

            // 가격 검증
            int price = product.getEffectivePrice();
            if (price <= 0) {
                throw new BusinessException(ErrorCode.INVALID_PRICE);
            }

            product.reserve(qty);

            products.add(product);
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
