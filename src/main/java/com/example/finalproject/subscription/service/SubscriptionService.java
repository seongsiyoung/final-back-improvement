package com.example.finalproject.subscription.service;

import com.example.finalproject.global.component.UserLoader;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.payment.service.SubscriptionBillingService;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.domain.SubscriptionStatusLog;
import com.example.finalproject.subscription.dto.request.PostSubscriptionRequest;
import com.example.finalproject.subscription.dto.response.GetSubscriptionResponse;
import com.example.finalproject.subscription.enums.SubHistoryStatus;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionHistoryRepository;
import com.example.finalproject.subscription.repository.SubscriptionProductDayOfWeekRepository;
import com.example.finalproject.subscription.repository.SubscriptionProductItemRepository;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.subscription.repository.SubscriptionStatusLogRepository;
import com.example.finalproject.user.domain.User;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private static final Set<SubscriptionStatus> LISTABLE_STATUSES =
            EnumSet.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAUSED, SubscriptionStatus.CANCELLATION_PENDING);

    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionProductItemRepository subscriptionProductItemRepository;
    private final SubscriptionProductDayOfWeekRepository subscriptionProductDayOfWeekRepository;
    private final SubscriptionHistoryRepository subscriptionHistoryRepository;
    private final SubscriptionStatusLogRepository subscriptionStatusLogRepository;
    private final UserLoader userLoader;
    private final SubscriptionBillingService subscriptionBillingService;
    private final SubscriptionStatusService subscriptionStatusService;
    private final SubscriptionCreationService subscriptionCreationService;

    /**
     * 고객의 구독 목록을 조회한다 (API-SUB-002). 해지 완료(CANCELLED)는 제외. 유저 조회는 UserLoader를 통해 수행한다.
     *
     * @param username 로그인한 사용자 식별자 (이메일)
     * @return 구독 목록 (구독 상품, 구독 상태, 다음 결제 예정일 등)
     */
    @Transactional(readOnly = true)
    public List<GetSubscriptionResponse> findListByUser(String username) {
        Long userId = userLoader.loadUserByUsername(username).getId();
        return findListByUserId(userId);
    }

    /**
     * 구독을 일시정지한다 (API-SUB-003). 본인 구독이며 ACTIVE 상태일 때만 가능.
     *
     * @param subscriptionId 구독 ID
     * @param username       로그인한 사용자 식별자 (이메일)
     * @throws BusinessException 구독 없음(SUBSCRIPTION_NOT_FOUND), 본인 구독 아님(SUBSCRIPTION_FORBIDDEN), 상태
     *                           불가(SUBSCRIPTION_INVALID_STATUS)
     */
    @Transactional
    public void pause(Long subscriptionId, String username) {
        Long userId = userLoader.loadUserByUsername(username).getId();
        Subscription subscription = getOwnSubscription(subscriptionId, userId);
        SubscriptionStatus before = subscription.getStatus();
        try {
            subscription.pause();
            saveStatusLog(subscription, before, SubscriptionStatus.PAUSED);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_INVALID_STATUS);
        }
    }

    /**
     * 일시정지된 구독을 재개한다 (API-SUB-004). 본인 구독이며 PAUSED 상태일 때만 가능.
     *
     * @param subscriptionId 구독 ID
     * @param username       로그인한 사용자 식별자 (이메일)
     * @throws BusinessException 구독 없음, 본인 구독 아님, 상태 불가
     */
    @Transactional
    public void resume(Long subscriptionId, String username) {
        Long userId = userLoader.loadUserByUsername(username).getId();
        Subscription subscription = getOwnSubscription(subscriptionId, userId);
        SubscriptionStatus before = subscription.getStatus();
        try {
            subscription.resume();
            saveStatusLog(subscription, before, SubscriptionStatus.ACTIVE);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_INVALID_STATUS);
        }
    }

    /**
     * 구독 해지를 요청한다 (API-SUB-005). 다음 결제일 기준 해지 정책에 따라 해지 예정(CANCELLATION_PENDING)으로 전환한다. 본인 구독이며 ACTIVE 또는 PAUSED 상태일
     * 때만 가능.
     *
     * @param subscriptionId 구독 ID
     * @param username       로그인한 사용자 식별자 (이메일)
     * @param reason         해지 사유 (선택, null 가능)
     * @throws BusinessException 구독 없음, 본인 구독 아님, 상태 불가
     */
    @Transactional
    public void cancel(Long subscriptionId, String username, String reason) {
        Long userId = userLoader.loadUserByUsername(username).getId();
        Subscription subscription = getOwnSubscription(subscriptionId, userId);
        SubscriptionStatus before = subscription.getStatus();
        try {
            subscription.requestCancellation(reason);
            saveStatusLog(subscription, before, SubscriptionStatus.CANCELLATION_PENDING);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_INVALID_STATUS);
        }
    }

    /**
     * 해지 예정을 취소하고 구독을 유지한다 (UC-C10 5-a). 본인 구독이며 CANCELLATION_PENDING 상태일 때만 가능.
     */
    @Transactional
    public void cancelCancellation(Long subscriptionId, String username) {
        Long userId = userLoader.loadUserByUsername(username).getId();
        Subscription subscription = getOwnSubscription(subscriptionId, userId);
        SubscriptionStatus before = subscription.getStatus();
        try {
            subscription.cancelCancellation();
            saveStatusLog(subscription, before, SubscriptionStatus.ACTIVE);
        } catch (IllegalStateException e) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_INVALID_STATUS);
        }
    }

    /**
     * 구독을 신청한다 (API-SUB-001). deliveryTimeSlot이 null이거나 빈 값이면 08:00~11:00을 기본값으로 사용한다.
     *
     * @param username 로그인한 사용자 식별자 (이메일)
     * @param request  구독 신청 요청
     * @return 생성된 구독 응답
     */
    public GetSubscriptionResponse create(String username, PostSubscriptionRequest request) {
        User user = userLoader.loadUserByUsername(username);
        Long userId = user.getId();

        Subscription subscription = subscriptionCreationService.createPendingSubscription(request, userId, user);

        try {
            subscriptionBillingService.chargeMonthlyFee(subscription.getId());
        } catch (Exception e) {
            subscriptionStatusService.markPaymentFailed(subscription.getId());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED);
        }

        // 결제는 이미 성공했다. 이후 활성화 반영이 실패해도 결제 실패로 잘못 기록하지 않는다 —
        // 그렇게 하면 "돈은 빠져나갔는데 구독 상태는 결제 실패"로 모순된 상태가 커밋된다.
        subscriptionStatusService.activateAfterFirstPayment(subscription.getId());

        return toResponse(subscription);
    }

    @Transactional(readOnly = true)
    private List<GetSubscriptionResponse> findListByUserId(Long userId) {
        List<Subscription> list = subscriptionRepository.findByUserIdAndStatusInOrderByCreatedAtDesc(userId,
                LISTABLE_STATUSES);
        return list.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 구독 ID와 사용자 ID로 본인 구독을 조회한다. 없거나 소유자가 아니면 예외.
     */
    private Subscription getOwnSubscription(Long subscriptionId, Long userId) {
        return subscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND));
    }

    private GetSubscriptionResponse toResponse(Subscription s) {
        var product = s.getSubscriptionProduct();
        List<Short> daysOfWeek = subscriptionProductDayOfWeekRepository.findBySubscriptionProductOrderById_DayOfWeekAsc(
                        product)
                .stream()
                .map(d -> d.getId().getDayOfWeek())
                .collect(Collectors.toList());
        if (daysOfWeek.isEmpty()) {
            int count = (product.getDeliveryCountOfWeek() != null && product.getDeliveryCountOfWeek() > 0)
                    ? Math.min(product.getDeliveryCountOfWeek(), 7)
                    : 1;
            daysOfWeek = java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> (short) (i + 1))
                    .collect(Collectors.toList());
        }
        var items = subscriptionProductItemRepository.findBySubscriptionProductOrderById(product)
                .stream()
                .map(i -> GetSubscriptionResponse.SubscriptionItemDto.builder()
                        .productName(i.getProduct().getProductName())
                        .quantity(i.getQuantity())
                        .build())
                .collect(Collectors.toList());
        int totalDeliveryCount = product.getTotalDeliveryCount() != null ? product.getTotalDeliveryCount() : 0;
        int completedDeliveryCount = (int) subscriptionHistoryRepository.countBySubscriptionAndStatus(s,
                SubHistoryStatus.COMPLETED);

        return GetSubscriptionResponse.builder()
                .subscriptionId(s.getId())
                .storeId(s.getStore().getId())
                .storeName(s.getStore().getStoreName())
                .subscriptionProductId(product.getId())
                .subscriptionProductName(product.getSubscriptionProductName())
                .status(s.getStatus())
                .totalAmount(s.getTotalAmount())
                .deliveryTimeSlot(s.getDeliveryTimeSlot())
                .nextPaymentDate(s.getNextPaymentDate())
                .totalDeliveryCount(totalDeliveryCount)
                .completedDeliveryCount(completedDeliveryCount)
                .daysOfWeek(daysOfWeek)
                .items(items)
                .startedAt(s.getStartedAt())
                .pausedAt(s.getPausedAt())
                .cancelledAt(s.getCancelledAt())
                .cancelReason(s.getCancelReason())
                .build();
    }

    /**
     * BR-C10-05: 구독 상태 변경 이력을 기록한다.
     */
    private void saveStatusLog(Subscription subscription, SubscriptionStatus fromStatus, SubscriptionStatus toStatus) {
        subscriptionStatusLogRepository.save(
                SubscriptionStatusLog.builder()
                        .subscription(subscription)
                        .fromStatus(fromStatus)
                        .toStatus(toStatus)
                        .createdAt(LocalDateTime.now())
                        .build()
        );
    }
}
