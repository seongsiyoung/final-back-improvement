package com.example.finalproject.subscription.repository;

import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.domain.SubscriptionProduct;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /**
     * 비관적 락으로 구독을 조회한다. SubscriptionChargeCommandService.startCharge()가
     * 같은 결제주기 중복 승인 방지 가드(존재 확인) 직후 새 SubscriptionPayment를 저장하는
     * 사이의 TOCTOU 레이스를 좁히는 데 쓴다 — 두 트랜잭션이 같은 구독에 대해 동시에
     * startCharge()를 호출해도 락을 먼저 획득한 쪽만 가드~저장 구간을 진행하고,
     * 나중 트랜잭션은 앞선 트랜잭션이 커밋할 때까지 대기한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Subscription> findWithLockById(Long id);

    /**
     * 마트(store)별로 특정 상태 집합에 속하는 구독 목록을 조회한다.
     *
     * @param storeId  마트 ID
     * @param statuses 조회할 상태 집합 (예: ACTIVE)
     * @return 구독 목록
     */
    List<Subscription> findByStoreIdAndStatusIn(Long storeId, Collection<SubscriptionStatus> statuses);

    /**
     * 고객(사용자)의 구독 목록을 조회한다. 해지 완료(CANCELLED)는 제외하고, 최신순 정렬 (UC-C10).
     *
     * @param userId   사용자 ID
     * @param statuses 조회할 상태 집합 (ACTIVE, PAUSED, CANCELLATION_PENDING)
     * @return 구독 목록
     */
    List<Subscription> findByUserIdAndStatusInOrderByCreatedAtDesc(Long userId,
                                                                   Collection<SubscriptionStatus> statuses);

    long countByUserIdAndStatusIn(Long userId, Collection<SubscriptionStatus> statuses);

    /**
     * 구독 ID와 소유 사용자 ID로 구독을 조회한다. 본인 구독 여부 검증용.
     *
     * @param id     구독 ID
     * @param userId 사용자 ID
     * @return 구독 (Optional)
     */
    Optional<Subscription> findByIdAndUserId(Long id, Long userId);

    /**
     * 구독 상품별로 활성(ACTIVE) 구독자 수를 센다.
     *
     * @param subscriptionProduct 구독 상품
     * @param status              구독 상태 (ACTIVE 등)
     * @return 해당 상태의 구독 건수
     */
    long countBySubscriptionProductAndStatus(SubscriptionProduct subscriptionProduct, SubscriptionStatus status);

    /**
     * 구독 상품에 대해 특정 상태 집합에 속하는 구독이 존재하는지 여부를 확인한다.
     *
     * @param subscriptionProduct 구독 상품
     * @param statuses            구독 상태 목록
     * @return true: 존재, false: 없음
     */
    boolean existsBySubscriptionProductAndStatusIn(SubscriptionProduct subscriptionProduct,
                                                   Collection<SubscriptionStatus> statuses);


    @Query("select s.id "
            + "from Subscription s "
            + "where s.status = :status "
            + "and s.nextPaymentDate <= :today")
    List<Long> findIdsByStatusAndNextPaymentDateLessThanEqual(SubscriptionStatus status, LocalDate today);

    /**
     * 결제 실패(PAYMENT_FAILED) 상태이면서 재시도 시각이 도래했고 아직 재시도 횟수를
     * 소진하지 않은 구독을 조회한다. SubscriptionBillingScheduler가 기존 ACTIVE 대상
     * 목록과 합쳐서 처리한다.
     */
    @Query("select s.id "
            + "from Subscription s "
            + "where s.status = :status "
            + "and s.nextRetryAt <= :now "
            + "and s.failCount < :maxRetries")
    List<Long> findIdsRetryTargets(SubscriptionStatus status, LocalDateTime now, int maxRetries);

    long countBySubscriptionProductAndStatusIn(SubscriptionProduct subscriptionProduct,
                                               Collection<SubscriptionStatus> statuses);

    /**
     * 구독 상품에 대해 특정 상태 집합에 속하는 구독 목록을 조회한다. 삭제 예정 알림 등 구독자별 알림 발송 시 사용. User를 fetch join하여 LazyInitializationException
     * 방지.
     *
     * @param subscriptionProduct 구독 상품
     * @param statuses            구독 상태 목록 (ACTIVE, PAUSED, CANCELLATION_PENDING 등)
     * @return 구독 목록
     */
    @Query("SELECT s FROM Subscription s JOIN FETCH s.user WHERE s.subscriptionProduct = :product AND s.status IN :statuses")
    List<Subscription> findBySubscriptionProductAndStatusIn(
            @Param("product") SubscriptionProduct subscriptionProduct,
            @Param("statuses") Collection<SubscriptionStatus> statuses);
}
