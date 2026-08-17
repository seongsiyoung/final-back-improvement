package com.example.finalproject.payment.service;

import com.example.finalproject.delivery.service.DeliveryFeeService;
import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.domain.OrderLine;
import com.example.finalproject.order.domain.OrderProduct;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.repository.OrderLineRepository;
import com.example.finalproject.order.repository.OrderProductRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.dto.request.TossConfirmRequest;
import com.example.finalproject.payment.dto.response.PostPaymentConfirmResponse;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.event.StoreOrderCreatedEvent;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.payment.dto.response.TossConfirmResponse;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.user.domain.User;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentConfirmCommandService {

    private final UserLoader userLoader;
    private final PaymentRepository paymentRepository;
    private final OrderLineRepository orderLineRepository;
    private final ProductRepository productRepository;
    private final DeliveryFeeService deliveryFeeService;
    private final StoreOrderRepository storeOrderRepository;
    private final StoreRepository storeRepository;
    private final OrderProductRepository orderProductRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    @Transactional
    public TossConfirmRequest startConfirm(String email, Long paymentId, String paymentKey) {
        User user = userLoader.loadUserByUsername(email);

        Payment payment = paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        Order order = payment.getOrder();

        if (!order.getUser().getId().equals(user.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }

        payment.markPending();

        return new TossConfirmRequest(
                paymentKey,
                payment.getPgOrderId(),
                payment.getAmount()
        );
    }

    @Transactional
    public PostPaymentConfirmResponse completeConfirm(Long paymentId, String paymentKey, TossConfirmResponse pg) {
        Payment payment = paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getPaymentStatus() != PaymentStatus.PENDING) {
            throw new BusinessException(ErrorCode.ALREADY_PROCESSED_PAYMENT);
        }

        Order order = payment.getOrder();
        List<OrderLine> lines = orderLineRepository.findAllByOrderId(order.getId());

        for (OrderLine line : lines) {
            Product product = productRepository.findByIdForUpdate(line.getProductId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

            if (product.getStock() < line.getQuantity()) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
            }

            product.decreaseStock(line.getQuantity());
        }

        List<StoreOrder> storeOrders = createStoreOrdersAndOrderProducts(order, lines);

        for (StoreOrder storeOrder : storeOrders) {
            applicationEventPublisher.publishEvent(
                    new StoreOrderCreatedEvent(
                            storeOrder.getId(),
                            storeOrder.getOrder().getOrderedAt()
                    )
            );
        }

        payment.approve(
                paymentKey,
                pg.getPaymentKey(),
                pg.getReceipt() != null ? pg.getReceipt().getUrl() : null
        );

        order.markPaid();

        return new PostPaymentConfirmResponse(
                order.getId(),
                payment.getId(),
                payment.getPaymentStatus().name(),
                payment.getPaidAt(),
                payment.getReceiptUrl()
        );
    }

    @Transactional
    public void revertPendingToReady(Long paymentId) {
        Payment payment = paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
            payment.revertToReady();
        }
    }

    @Transactional
    public void failPending(Long paymentId) {
        Payment payment = paymentRepository.findWithLockById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
            payment.fail();
        }
    }

    private List<StoreOrder> createStoreOrdersAndOrderProducts(Order order, List<OrderLine> lines) {
        Map<Long, List<OrderLine>> grouped = lines.stream()
                .collect(Collectors.groupingBy(OrderLine::getStoreId));

        List<StoreOrder> createdStoreOrders = new ArrayList<>();

        for (Map.Entry<Long, List<OrderLine>> entry : grouped.entrySet()) {
            Long storeId = entry.getKey();
            List<OrderLine> storeLines = entry.getValue();

            int storeProductPrice = storeLines.stream()
                    .mapToInt(l -> l.getPriceSnapshot() * l.getQuantity())
                    .sum();

            int deliveryFee =
                    deliveryFeeService.calculateDeliveryFee(
                            order.getUser().getId(),
                            storeId
                    );

            int finalPrice = storeProductPrice + deliveryFee;

            Store store = storeRepository.findById(storeId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));

            StoreOrder storeOrder = storeOrderRepository.save(
                    StoreOrder.builder()
                            .order(order)
                            .store(store)
                            .orderType(order.getOrderType())
                            .storeProductPrice(storeProductPrice)
                            .deliveryFee(deliveryFee)
                            .finalPrice(finalPrice)
                            .build()
            );

            createdStoreOrders.add(storeOrder);

            List<Long> productIds = storeLines.stream()
                    .map(OrderLine::getProductId)
                    .toList();

            Map<Long, Product> productMap = productRepository
                    .findAllByIdInAndDeletedAtIsNull(productIds)
                    .stream()
                    .collect(Collectors.toMap(Product::getId, Function.identity()));

            List<OrderProduct> orderProducts = storeLines.stream()
                    .map(line -> {
                        Product product = productMap.get(line.getProductId());
                        if (product == null) {
                            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
                        }
                        return OrderProduct.of(storeOrder, product, line);
                    })
                    .toList();

            orderProductRepository.saveAll(orderProducts);
        }

        return createdStoreOrders;
    }

}
