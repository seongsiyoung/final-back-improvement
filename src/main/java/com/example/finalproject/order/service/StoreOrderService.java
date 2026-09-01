package com.example.finalproject.order.service;

import com.example.finalproject.delivery.component.DeliveryMatchComponent;
import com.example.finalproject.delivery.domain.Delivery;
import com.example.finalproject.delivery.enums.DeliveryStatus;
import com.example.finalproject.delivery.repository.DeliveryRepository;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;

import com.example.finalproject.global.sse.Service.SseService;
import com.example.finalproject.global.sse.enums.SseEventType;
import com.example.finalproject.order.domain.OrderProduct;
import com.example.finalproject.order.domain.StoreOrder;
import com.example.finalproject.order.dto.storeorder.request.PatchStoreOrderAcceptRequest;
import com.example.finalproject.order.dto.storeorder.response.GetCompletedStoreOrderResponse;
import com.example.finalproject.order.dto.storeorder.response.GetStoreSalesResponse;
import com.example.finalproject.order.dto.storeorder.response.GetStoreOrderResponse;
import com.example.finalproject.order.enums.OrderStatus;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.enums.StoreOrderStatus;
import com.example.finalproject.order.event.StoreOrderAcceptedEvent;
import com.example.finalproject.order.repository.OrderProductRepository;
import com.example.finalproject.order.repository.StoreOrderRepository;
import com.example.finalproject.payment.repository.PaymentRefundRepository;
import com.example.finalproject.payment.service.RefundTarget;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.domain.StoreBusinessHour;
import com.example.finalproject.store.enums.StoreActiveStatus;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.user.domain.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StoreOrderService {

    private final StoreOrderRepository storeOrderRepository;
    private final OrderProductRepository orderProductRepository;
    private final StoreRepository storeRepository;
    private final DeliveryRepository deliveryRepository;
    private final DeliveryMatchComponent deliveryMatchComponent;
    private final PaymentRefundRepository paymentRefundRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SseService sseService;
    private final StoreOrderTtlService storeOrderTtlService;
    private final StoreOrderAutoRejectService storeOrderAutoRejectService;
    private final StoreOrderRejectCommandService storeOrderRejectCommandService;
    private final StoreOrderRejectService storeOrderRejectService;
    private final StoreOrderAutoReadyService storeOrderAutoReadyService;

    public List<GetStoreOrderResponse> getNewOrders(String userEmail) {
        log.info("신규 주문 조회 시작 - userEmail={}", userEmail);
        Store store = getStoreByOwner(userEmail);
        List<StoreOrderStatus> statuses = List.of(StoreOrderStatus.PENDING, StoreOrderStatus.ACCEPTED,
                StoreOrderStatus.READY);

        List<StoreOrder> storeOrdersBeforeReady = storeOrderRepository.findByStoreIdAndStatusIn(store.getId(),
                statuses);
        List<OrderProduct> orderProducts = orderProductRepository.findByStoreOrderIn(storeOrdersBeforeReady);
        List<Delivery> deliveries = deliveryRepository.findByStoreOrderIdIn(
                storeOrdersBeforeReady.stream().map(StoreOrder::getId).toList());

        Map<Long, List<OrderProduct>> orderProductsByStoreOrderId = orderProducts.stream()
                .collect(Collectors.groupingBy(orderProduct -> orderProduct.getStoreOrder().getId()));

        Map<Long, DeliveryStatus> deliveryStatusByStoreOrderId = deliveries.stream()
                .collect(Collectors.toMap(d -> d.getStoreOrder().getId(), Delivery::getStatus));

        List<GetStoreOrderResponse> result = storeOrdersBeforeReady.stream()
                .filter(storeOrder -> {
                    List<OrderProduct> products = orderProductsByStoreOrderId.get(storeOrder.getId());
                    if (products == null || products.isEmpty()) {
                        log.error("주문 상품 데이터 누락 - storeOrderId={}", storeOrder.getId());
                        return false;
                    }
                    return true;
                })
                .map(storeOrder -> GetStoreOrderResponse.from(storeOrder,
                        orderProductsByStoreOrderId.get(storeOrder.getId()),
                        deliveryStatusByStoreOrderId.get(storeOrder.getId())))
                .toList();
        log.info("신규 주문 조회 완료 - storeId={}, count={}", store.getId(), result.size());
        return result;
    }

    @Transactional
    public void acceptOrder(Long storeOrderId, PatchStoreOrderAcceptRequest request, String userEmail) {
        log.info("주문 접수 시작 - storeOrderId={}, userEmail={}", storeOrderId, userEmail);
        Store store = getStoreByOwner(userEmail);
        StoreOrder storeOrder = getStoreOrderWithOrderAndUser(storeOrderId);

        validateStoreOwnership(storeOrder, store);
        validateActiveStore(store);
        validateDeliveryAvailable(store);
        validateBusinessHour(store);

        OrderStatus orderStatus = storeOrder.getOrder().getStatus();
        validateOrderPaid(orderStatus);

        storeOrder.accept(request.getPrepTime());

        storeOrderTtlService.removeAutoReject(storeOrderId);
        storeOrderTtlService.setAutoReady(storeOrderId, storeOrder.getAcceptedAt(), request.getPrepTime());

        User customer = storeOrder.getOrder().getUser();

        // 배달 생성
        Delivery delivery = Delivery.builder()
                .storeOrder(storeOrder)
                .deliveryFee(storeOrder.getDeliveryFee())
                .storeLocation(store.getAddress().getLocation())
                .customerLocation(storeOrder.getOrder().getDeliveryLocation())
                .build();

        if (delivery == null) {
            throw new IllegalStateException("Delivery creation failed");
        }
        deliveryRepository.save(delivery);

        // 라이더 배달 알림 트리거 (Redis GEO 등록 및 SSE 알림)
        deliveryMatchComponent.notifyNewDelivery(delivery);

        // 접수 알림
        eventPublisher.publishEvent(new StoreOrderAcceptedEvent(customer.getId(),
                storeOrder.getOrder().getOrderNumber(), store.getStoreName()));
        log.info("주문 접수 완료 - storeOrderId={}, prepTime={}, deliveryId={}", storeOrderId, request.getPrepTime(),
                delivery.getId());

    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void rejectOrder(Long storeOrderId, String username, String reason) {
        log.info("주문 거절 시작 - storeOrderId={}, username={}", storeOrderId, username);
        Store store = getStoreByOwner(username);

        RefundTarget target = storeOrderRejectCommandService.requestRejectByOwner(
                storeOrderId, store.getId(), reason);
        storeOrderRejectService.reject(target);

        log.info("주문 거절 요청 완료(환불 처리 대기) - storeOrderId={}, reason={}", storeOrderId, reason);
    }

    @Transactional
    public void completePreparation(Long storeOrderId, String username) {
        log.info("준비 완료 처리 시작 - storeOrderId={}, username={}", storeOrderId, username);
        Store store = getStoreByOwner(username);
        StoreOrder storeOrder = getStoreOrder(storeOrderId);

        validateStoreOwnership(storeOrder, store);
        validateActiveStore(store);
        validateBusinessHour(store);

        if (storeOrder.getStatus() != StoreOrderStatus.ACCEPTED) {
            throw new BusinessException(ErrorCode.STORE_ORDER_NOT_ACCEPTED);
        }

        storeOrderTtlService.removeAutoReady(storeOrderId);
        storeOrder.markReady();
        log.info("준비 완료 처리 완료 - storeOrderId={}", storeOrderId);
    }

    public List<GetCompletedStoreOrderResponse> getCompletedOrders(String username) {
        log.info("완료 주문 조회 시작 - username={}", username);
        Store store = getStoreByOwner(username);
        List<StoreOrderStatus> statuses = List.of(
                StoreOrderStatus.PICKED_UP, StoreOrderStatus.DELIVERING);

        List<StoreOrder> completedOrders = storeOrderRepository.findCompletedByStoreIdAndStatusIn(store.getId(),
                statuses);
        List<OrderProduct> orderProducts = orderProductRepository.findByStoreOrderIn(completedOrders);

        Map<Long, List<OrderProduct>> orderProductsByStoreOrderId = orderProducts.stream()
                .collect(Collectors.groupingBy(op -> op.getStoreOrder().getId()));

        List<GetCompletedStoreOrderResponse> result = completedOrders.stream()
                .filter(storeOrder -> {
                    List<OrderProduct> products = orderProductsByStoreOrderId.get(storeOrder.getId());
                    if (products == null || products.isEmpty()) {
                        log.error("주문 상품 데이터 누락 - storeOrderId={}", storeOrder.getId());
                        return false;
                    }
                    return true;
                })
                .map(storeOrder -> GetCompletedStoreOrderResponse.from(storeOrder,
                        orderProductsByStoreOrderId.get(storeOrder.getId())))
                .toList();
        log.info("완료 주문 조회 완료 - storeId={}, count={}", store.getId(), result.size());
        return result;
    }

    /**
     * 자동 상태 변경 스케줄러. - 5분 이상 응답 없는 PENDING 주문은 자동 거절 - 준비 완료 시간이 지난 ACCEPTED 주문은 자동
     * READY 처리
     * <p>
     * 프론트에서의 타이머 의존도를 줄이고, 사장님이 대시보드를 보고 있지 않아도 백엔드 기준으로 상태가 일관되게 유지되도록 한다.
     */
    @Scheduled(fixedDelay = 300_000L)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processTimedOutStoreOrders() {
        LocalDateTime now = LocalDateTime.now();

        autoRejectExpiredPendingOrders(now);
        autoMarkReadyAcceptedOrders(now);
    }

    private void autoRejectExpiredPendingOrders(LocalDateTime now) {
        LocalDateTime pendingCutoff = now.minusMinutes(5);

        List<StoreOrder> expiredPendingOrders = storeOrderRepository
                .findByStatusAndCreatedAtBefore(StoreOrderStatus.PENDING, pendingCutoff);

        if (expiredPendingOrders.isEmpty()) {
            return;
        }

        log.info("자동 거절 대상 신규 주문 수: {}", expiredPendingOrders.size());

        for (StoreOrder storeOrder : expiredPendingOrders) {
            try {
                storeOrderAutoRejectService.rejectSingleOrder(storeOrder.getId());
            } catch (Exception e) {
                log.error("자동 거절 처리 중 오류 발생 - storeOrderId={}", storeOrder.getId(), e);
            }
        }
    }

    private void autoMarkReadyAcceptedOrders(LocalDateTime now) {
        List<StoreOrder> acceptedOrders = storeOrderRepository.findByStatus(StoreOrderStatus.ACCEPTED);

        if (acceptedOrders.isEmpty()) {
            return;
        }

        log.info("자동 준비 완료 대상 확인 중 - acceptedOrders={}", acceptedOrders.size());

        for (StoreOrder storeOrder : acceptedOrders) {
            try {
                LocalDateTime acceptedAt = storeOrder.getAcceptedAt();
                Integer prepTime = storeOrder.getPrepTime();

                if (acceptedAt == null || prepTime == null) {
                    continue;
                }

                LocalDateTime readyAt = acceptedAt.plusMinutes(prepTime);

                if (!readyAt.isAfter(now)) {
                    storeOrderAutoReadyService.markReadySingleOrder(storeOrder.getId());
                }
            } catch (Exception e) {
                log.error("자동 준비 완료 처리 중 오류 발생 - storeOrderId={}", storeOrder.getId(), e);
            }
        }
    }

    public Page<GetCompletedStoreOrderResponse> getAllOrders(String username, Pageable pageable) {
        Store store = getStoreByOwner(username);
        Page<StoreOrder> storeOrderPage = storeOrderRepository.findAllByStoreId(store.getId(), pageable);
        List<StoreOrder> storeOrders = storeOrderPage.getContent();

        List<OrderProduct> orderProducts = storeOrders.isEmpty()
                ? Collections.emptyList()
                : orderProductRepository.findByStoreOrderIn(storeOrders);

        Map<Long, List<OrderProduct>> orderProductsByStoreOrderId = orderProducts.stream()
                .collect(Collectors.groupingBy(op -> op.getStoreOrder().getId()));

        return storeOrderPage.map(storeOrder -> {
            List<OrderProduct> products = orderProductsByStoreOrderId.getOrDefault(storeOrder.getId(),
                    Collections.emptyList());
            if (products.isEmpty()) {
                log.error("주문 상품 데이터 누락 - storeOrderId={}", storeOrder.getId());
            }
            return GetCompletedStoreOrderResponse.from(storeOrder, products);
        });
    }

    public GetStoreSalesResponse getMonthlySales(String userEmail, int year, int month) {
        log.info("월별 매출 조회 시작 - userEmail={}, year={}, month={}", userEmail, year, month);
        Store store = getStoreByOwner(userEmail);
        Long storeId = store.getId();

        // 해당 월 범위
        YearMonth yearMonth = YearMonth.of(year, month);
        LocalDateTime monthStart = yearMonth.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = yearMonth.atEndOfMonth().atTime(LocalTime.MAX);

        // 전월 범위
        YearMonth prevMonth = yearMonth.minusMonths(1);
        LocalDateTime prevMonthStart = prevMonth.atDay(1).atStartOfDay();
        LocalDateTime prevMonthEnd = prevMonth.atEndOfMonth().atTime(LocalTime.MAX);

        // 오늘/어제 범위
        LocalDate today = LocalDate.now();
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);
        LocalDateTime yesterdayStart = today.minusDays(1).atStartOfDay();
        LocalDateTime yesterdayEnd = today.minusDays(1).atTime(LocalTime.MAX);

        // 주문 유형별 건수
        long regularCount = storeOrderRepository.countByStoreIdAndStatusAndOrderTypeAndDeliveredAtBetween(storeId,
                StoreOrderStatus.DELIVERED, OrderType.REGULAR, monthStart, monthEnd);
        long subscriptionCount = storeOrderRepository.countByStoreIdAndStatusAndOrderTypeAndDeliveredAtBetween(storeId,
                StoreOrderStatus.DELIVERED, OrderType.SUBSCRIPTION, monthStart, monthEnd);

        // 총 매출 (해당 월)
        long totalSales = storeOrderRepository.sumFinalPriceByStoreIdAndStatusAndDeliveredAtBetween(storeId,
                StoreOrderStatus.DELIVERED, monthStart, monthEnd);

        // 전월 매출 → 전월 대비 증감률
        long prevMonthSales = storeOrderRepository.sumFinalPriceByStoreIdAndStatusAndDeliveredAtBetween(storeId,
                StoreOrderStatus.DELIVERED, prevMonthStart, prevMonthEnd);
        double monthOverMonthRate = prevMonthSales == 0 ? 0.0
                : ((double) (totalSales - prevMonthSales) / prevMonthSales) * 100;

        // 환불 금액 (상점 기준: 배달비 제외, storeProductPrice만)
        long refundAmount = paymentRefundRepository.sumStoreProductPriceByStoreOrderStoreIdAndRefundedAtBetween(storeId,
                monthStart, monthEnd);

        // 환불 건수 (CANCELLED + REJECTED)
        List<StoreOrderStatus> refundStatuses = List.of(StoreOrderStatus.CANCELLED, StoreOrderStatus.REJECTED);
        long refundCount = storeOrderRepository.countByStoreIdAndStatusInAndCancelledAtBetween(storeId, refundStatuses,
                monthStart, monthEnd);

        // 총 주문 건수
        long totalOrderCount = regularCount + subscriptionCount;

        // 평균 주문 금액
        long averageOrderAmount = totalOrderCount == 0 ? 0 : totalSales / totalOrderCount;

        // 플랫폼 수수료 (8%)
        long platformFee = (long) (totalSales * 0.08);

        // 오늘/어제 매출 → 일간 증감률 (요청 연월이 이번 달일 때만 사용)
        YearMonth currentYearMonth = YearMonth.now();
        long todaySales = 0;
        long yesterdaySales = 0;
        double dayOverDayRate = 0.0;
        if (yearMonth.equals(currentYearMonth)) {
            todaySales = storeOrderRepository.sumFinalPriceByStoreIdAndStatusAndDeliveredAtBetween(storeId,
                    StoreOrderStatus.DELIVERED, todayStart, todayEnd);
            yesterdaySales = storeOrderRepository.sumFinalPriceByStoreIdAndStatusAndDeliveredAtBetween(storeId,
                    StoreOrderStatus.DELIVERED, yesterdayStart, yesterdayEnd);
            dayOverDayRate = yesterdaySales == 0 ? 0.0
                    : ((double) (todaySales - yesterdaySales) / yesterdaySales) * 100;
            dayOverDayRate = Math.round(dayOverDayRate * 10) / 10.0;
        }

        log.info("월별 매출 조회 완료 - storeId={}, totalSales={}, totalOrderCount={}",
                storeId, totalSales, totalOrderCount);

        return GetStoreSalesResponse.builder()
                .year(year)
                .month(month)
                .regularOrderCount(regularCount)
                .subscriptionOrderCount(subscriptionCount)
                .totalSales(totalSales)
                .monthOverMonthRate(Math.round(monthOverMonthRate * 10) / 10.0)
                .platformFee(platformFee)
                .refundAmount(refundAmount)
                .refundCount(refundCount)
                .totalOrderCount(totalOrderCount)
                .averageOrderAmount(averageOrderAmount)
                .dayOverDayRate(dayOverDayRate)
                .todaySales(todaySales)
                .yesterdaySales(yesterdaySales)
                .build();
    }

    /**
     * Redis TTL 만료 시 호출. PENDING 주문 자동 거절 후 스토어 오너에게 목록 갱신 SSE 발송. 이미 다른 경로(스케줄러
     * 등)에서 거절된 경우에도 목록 갱신 SSE는 발송.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processAutoRejectByTtl(Long storeOrderId) {
        log.info("[TTL][추적] processAutoRejectByTtl 진입 - storeOrderId={}", storeOrderId);
        RefundTarget target = storeOrderRejectCommandService.requestRejectIfPending(
                storeOrderId, "자동 거절 (미응답)");
        if (target != null) {
            storeOrderRejectService.reject(target);
            log.info("[TTL][추적] processAutoRejectByTtl - REJECT_REQUESTED 반영 및 PG 환불 요청 완료 storeOrderId={}",
                    storeOrderId);
        } else {
            log.info("[TTL][추적] 이미 PENDING 아님, DB 변경 없이 목록 갱신 SSE 만 발송 storeOrderId={}", storeOrderId);
        }
        Long ownerId = storeOrderRejectCommandService.findOwnerId(storeOrderId);
        if (ownerId == null) {
            return;
        }
        log.info("[TTL][추적] processAutoRejectByTtl - 스토어 오너 목록 갱신 SSE 발송 직전 ownerId={}, storeOrderId={}", ownerId,
                storeOrderId);
        sseService.send(ownerId, SseEventType.STORE_ORDER_UPDATED, storeOrderId);
        log.info("[TTL] 자동 거절 흐름 완료(목록 갱신 SSE 발송됨) - storeOrderId={}", storeOrderId);
    }

    /**
     * Redis TTL 만료 시 호출. ACCEPTED 주문 자동 준비완료 후 스토어 오너에게 목록 갱신 SSE 발송.
     */
    @Transactional
    public void processAutoMarkReadyByTtl(Long storeOrderId) {
        log.info("[TTL][추적] processAutoMarkReadyByTtl 진입 - storeOrderId={}", storeOrderId);
        StoreOrder storeOrder = storeOrderRepository.findByIdWithStoreAndOwner(storeOrderId).orElse(null);
        if (storeOrder == null) {
            log.warn("[TTL][추적] processAutoMarkReadyByTtl - 주문 없음, 스킵 storeOrderId={}", storeOrderId);
            return;
        }
        log.info("[TTL][추적] processAutoMarkReadyByTtl - 현재 상태 status={}, storeOrderId={}", storeOrder.getStatus(),
                storeOrderId);
        if (storeOrder.getStatus() != StoreOrderStatus.ACCEPTED) {
            log.info("[TTL][추적] processAutoMarkReadyByTtl - ACCEPTED 아님, 스킵(목록 갱신 SSE는 미발송) storeOrderId={}, status={}",
                    storeOrderId, storeOrder.getStatus());
            return;
        }
        storeOrder.markReady();
        Long ownerId = storeOrder.getStore().getOwner().getId();
        log.info("[TTL][추적] processAutoMarkReadyByTtl - 스토어 오너 목록 갱신 SSE 발송 직전 ownerId={}, storeOrderId={}", ownerId,
                storeOrderId);
        sseService.send(ownerId, SseEventType.STORE_ORDER_UPDATED, storeOrderId);
        log.info("[TTL] 자동 준비완료 흐름 완료 - storeOrderId={}", storeOrderId);
    }

    private Store getStoreByOwner(String userEmail) {
        return storeRepository.findByOwnerEmail(userEmail)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
    }

    private StoreOrder getStoreOrder(Long storeOrderId) {
        if (storeOrderId == null) {
            throw new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND);
        }
        return storeOrderRepository.findById(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));
    }

    private StoreOrder getStoreOrderWithOrderAndUser(Long storeOrderId) {
        return storeOrderRepository.findByIdWithOrderAndUser(storeOrderId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_ORDER_NOT_FOUND));
    }

    private void validateOrderPaid(OrderStatus orderStatus) {
        if (orderStatus != OrderStatus.PAID) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAID);
        }
    }

    private void validateStoreOwnership(StoreOrder storeOrder, Store store) {
        if (!storeOrder.getStore().getId().equals(store.getId())) {
            throw new BusinessException(ErrorCode.STORE_ORDER_NOT_BELONG_TO_STORE);
        }
    }

    private void validateActiveStore(Store store) {
        if (store.getIsActive() != StoreActiveStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.STORE_NOT_ACTIVE);
        }
    }

    private void validateDeliveryAvailable(Store store) {
        if (!Boolean.TRUE.equals(store.getIsDeliveryAvailable())) {
            throw new BusinessException(ErrorCode.STORE_DELIVERY_UNAVAILABLE);
        }
    }

    private void validateBusinessHour(Store store) {
        LocalDateTime now = LocalDateTime.now();
        int v = now.getDayOfWeek().getValue();
        short dayOfWeek = (short) (v == 7 ? 0 : v);
        LocalTime currentTime = now.toLocalTime();

        StoreBusinessHour businessHour = store.getBusinessHours().stream()
                .filter(bh -> bh.getDayOfWeek().equals(dayOfWeek)).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_OUTSIDE_BUSINESS_HOURS));

        if (businessHour.getIsClosed()) {
            throw new BusinessException(ErrorCode.STORE_OUTSIDE_BUSINESS_HOURS);
        }

        if (currentTime.isBefore(businessHour.getOpenTime()) || currentTime.isAfter(businessHour.getCloseTime())) {
            throw new BusinessException(ErrorCode.STORE_OUTSIDE_BUSINESS_HOURS);
        }
    }
}
