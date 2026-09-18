# PROJECT_INDEX.md — 동네 마켓 Backend

> 최종 갱신: 2026-02-21 | Java 파일: 538개 | 브랜치: fix/store-api-uc-s06-s08

---

## 1. 프로젝트 개요

| 항목 | 값 |
|------|----|
| Base Package | `com.example.finalproject` |
| Entrypoint | `FinalProjectApplication.java` |
| 역할 | CUSTOMER / STORE(마트사장) / RIDER / ADMIN |
| 포트 | 8080 (backend) / 5173 (frontend) |

---

## 2. 도메인 패키지 일람 (18개)

| # | 패키지 | 주요 Entity | 비고 |
|---|--------|------------|------|
| 1 | `admin` | *(Entity 없음)* | 관리자 사용자/신고/브로드캐스트/금융통계 |
| 2 | `auth` | PhoneVerification, RefreshToken | JWT+OAuth2(Kakao/Naver)+SMS |
| 3 | `user` | User, Role, UserRole, Address, SocialLogin, UserStatusHistory | 회원탈퇴 규칙 포함 |
| 4 | `store` | Store, StoreCategory, StoreBusinessHour | embedded: StoreAddress, SettlementAccount, SubmittedDocumentInfo |
| 5 | `product` | Product, ProductCategory, ProductStockHistory | StockEventType |
| 6 | `order` | Order, OrderLine, OrderProduct, StoreOrder, Cart, CartProduct | TTL 자동 처리 |
| 7 | `checkout` | *(Entity 없음)* | PriceCalculator 전략 패턴 |
| 8 | `delivery` | Delivery, DeliveryPhoto, Rider, RiderLocation | Redis 위치 추적 |
| 9 | `payment` | Payment, PaymentMethod, PaymentRefund, SubscriptionPayment | Toss Payments |
| 10 | `subscription` | Subscription, SubscriptionProduct, SubscriptionProductItem, SubscriptionDayOfWeek, SubscriptionProductDayOfWeek, SubscriptionHistory, SubscriptionStatusLog | 정기구독 |
| 11 | `settlement` | Settlement, SettlementDetail, RiderSettlementDetail | store/rider 서브패키지 분리 |
| 12 | `review` | Review | 사장 답글 포함 |
| 13 | `moderation` | Approval, ApprovalDocument, Report | 마트/라이더 입점 심사, 신고 |
| 14 | `content` | Faq, Notice, Banner, Promotion, PromotionProduct | |
| 15 | `communication` | Inquiry, Notification, NotificationBroadcast | SSE + DB 알림 |
| 16 | `coupon` | Coupon | CouponController, CouponRepository |
| 17 | `global` | BaseTimeEntity | 공통 인프라 |

---

## 3. 컨트롤러 (39개)

### 인증/사용자
| Controller | 패키지 |
|-----------|--------|
| `AuthController` | auth/controller |
| `UserController` | user/controller |
| `AddressController` | user/controller |
| `PasswordResetController` | user/controller |

### 관리자
| Controller | 패키지 |
|-----------|--------|
| `AdminUserController` | admin/controller |
| `AdminBroadcastController` | admin/controller |
| `AdminReportController` | admin/controller |
| `AdminLegacyController` | admin/controller |
| `AdminFinanceController` | admin/controller/finance |

### 마트
| Controller | 패키지 |
|-----------|--------|
| `StoreController` | store/controller |
| `StoreCustomerController` | store/controller |
| `StoreSubscriptionController` | store/controller |
| `StoreSubscriptionProductController` | store/controller |

### 상품
| Controller | 패키지 |
|-----------|--------|
| `ProductController` | product/controller |

### 주문/결제
| Controller | 패키지 |
|-----------|--------|
| `CartController` | order/controller |
| `OrderController` | order/controller |
| `StoreOrderController` | order/controller |
| `StoreOrderCancelController` | order/controller |
| `CheckoutController` | checkout/controller |
| `PaymentController` | payment/controller |
| `BillingController` | payment/controller |
| `CouponController` | coupon/controller |

### 배달
| Controller | 패키지 |
|-----------|--------|
| `RiderController` | delivery/controller |
| `CustomerDeliveryTrackingController` | delivery/controller |

### 정산
| Controller | 패키지 |
|-----------|--------|
| `StoreSettlementController` | settlement/store/controller |

### 구독
| Controller | 패키지 |
|-----------|--------|
| `SubscriptionController` | subscription/controller |

### 컨텐츠
| Controller | 패키지 |
|-----------|--------|
| `FaqController` | content/controller |
| `CustomerFaqController` | content/controller |
| `NoticeController` | content/controller |
| `CustomerNoticeController` | content/controller |
| `BannerController` | content/controller |
| `CustomerBannerController` | content/controller |

### 커뮤니케이션
| Controller | 패키지 |
|-----------|--------|
| `InquiryController` | communication/controller |
| `AdminInquiryController` | communication/controller |
| `NotificationController` | communication/controller |

### 승인
| Controller | 패키지 |
|-----------|--------|
| `AdminStoreApprovalController` | moderation/controller/admin/store |
| `AdminRiderApprovalController` | moderation/controller/admin/rider |

### 리뷰 / 파일
| Controller | 패키지 |
|-----------|--------|
| `ReviewController` | review/controller |
| `StorageController` | global/storage/controller |

---

## 4. 서비스 (57개 구현체 + 인터페이스)

### 인증/사용자
`AuthService` · `KakaoService` · `NaverService` · `SmsService` · `RefreshTokenStore`
`UserServiceImpl` · `AddressService` · `PasswordResetService` · `PasswordResetTokenService`

### 관리자
`AdminUserService` · `AdminReportService` · `AdminBroadcastService` · `AdminFinanceService`

### 마트/상품
`StoreService` · `StoreDeliveryScheduleService` · `StoreSubscriptionDeliveryService`
`ProductService`

### 주문
`CartService` · `OrderQueryService`
`StoreOrderService` · `StoreOrderStatusService` · `StoreOrderCancelService`
`StoreOrderTtlService` · `StoreOrderAutoReadyService` · `StoreOrderAutoRejectService`
`CheckoutService` · `DefaultPriceCalculator`

### 결제
`PaymentService` · `PaymentCommandService` · `PaymentCancelService`
`BillingService` · `SubscriptionBillingService` · `TossPaymentGateway`

### 배달
`DeliveryServiceImpl` · `RiderServiceImpl` · `RiderLocationServiceImpl`
`CustomerDeliveryTrackingServiceImpl` · `DeliveryFeeService` · `DeliveryMatchComponent`

### 정산
`StoreSettlementServiceImpl`

### 구독
`SubscriptionService` · `SubscriptionCreationService` · `SubscriptionProductService`
`SubscriptionOrderCreationService` · `SubscriptionStatusService` · `SubscriptionDeliveryCompletionService`

### 커뮤니케이션
`InquiryService` · `NotificationService` · `DbNotificationService` · `OrderPaidNotificationService`

### 컨텐츠
`FaqService` · `NoticeService` · `BannerService`

### 승인
`AdminStoreApprovalService` · `AdminRiderApprovalService`

### 리뷰
`ReviewService`

### global
`SseService` · `S3StorageService` · `S3DocumentStorageService` · `S3DeliveryStorageService`
`EmailSender`

---

## 5. 스케줄러 / 배치 (4개 스케줄러 + 2 Batch Job)

| 스케줄러 | 패키지 | 역할 |
|---------|--------|------|
| `SubscriptionOrderCreationScheduler` | subscription/scheduler | 정기 구독 자동 주문 생성 |
| `SubscriptionBillingScheduler` | payment/scheduler | 구독 자동 결제 |
| `StoreSettlementScheduler` | settlement/store/batch | 마트 정산 배치 |
| `RiderSettlementScheduler` | settlement/rider/scheduler | 라이더 주간 정산 배치 |

**Batch Jobs**: `StoreSettlementBatchConfig` · `RiderSettlementBatchConfig`
**Launchers**: `StoreSettlementBatchLauncher` · `RiderSettlementBatchLauncher`

---

## 6. 이벤트 시스템

### 이벤트 클래스
| Event | 발생 위치 |
|-------|---------|
| `StoreOrderCreatedEvent` | payment/event |
| `StoreOrderAcceptedEvent` | order/event |
| `StoreOrderRejectedEvent` | order/event |
| `StoreOrderRefundCompletedEvent` | order/event |
| `DeliveryStatusChangedEvent` | delivery/event |
| `InquiryAnsweredEvent` | communication/event |
| `UnreadCountChangedEvent` | communication/event |
| `PasswordResetRequestedEvent` | user/event |

### 리스너
| Listener | 역할 |
|---------|------|
| `StoreOrderSseListener` | 마트에 SSE 알림 |
| `StoreOrderRedisTtlListener` | Redis TTL 설정 |
| `StoreOrderNotificationListener` | DB 알림 저장 |
| `StoreOrderRefundListener` | 환불 처리 |
| `StoreOrderTtlExpirationListener` | TTL 만료 처리 |
| `InquiryAnsweredNotificationListener` | 문의 답변 알림 |
| `UnreadCountSseListener` | 읽지 않은 알림 수 SSE |
| `DeliveryEventListener` | 배달 상태 알림 |
| `PasswordResetEmailListener` | 비밀번호 재설정 이메일 |

---

## 7. SSE 이벤트 타입

| SseEventType | 이벤트명 |
|-------------|---------|
| `CONNECTED` | `connected` |
| `UNREAD_COUNT` | `unread-count` |
| `STORE_ORDER_CREATED` | `store-order-created` |
| `NEW_DELIVERY` | `new-delivery` |
| `NEARBY_DELIVERIES` | `nearby-deliveries` |
| `DELIVERY_MATCHED` | `delivery-matched` |

---

## 8. 에러 코드 체계

| 도메인 | 코드 범위 | 수량 |
|--------|----------|------|
| COMMON | COMMON-000 ~ 006 | 7개 |
| STORE | STORE-001 ~ 015 | 15개 |
| AUTH | AUTH-001 ~ 017 | 17개 |
| STORAGE | STORAGE-001 ~ 002 | 2개 |
| FAQ | FAQ-001 | 1개 |
| NOTICE | NOTICE-001 | 1개 |
| BANNER | BANNER-001 | 1개 |
| PRODUCT | PRODUCT-001 ~ 016 | 16개 |
| SUBSCRIPTION | SUBSCRIPTION-001 ~ 010 | 10개 |
| CART | CART-001 ~ 002 | 2개 |
| ADDRESS | ADDRESS-001 ~ 005 | 5개 |
| DELIVERY | DELIVERY-001 ~ 008 | 8개 |
| RIDER | RIDER-001 ~ 005 | 5개 |
| APPROVAL | APPROVAL-001 ~ 003 | 3개 |
| INQUIRY | INQUIRY-001 ~ 002 | 2개 |
| ADMIN | ADMIN-001 | 1개 |
| NOTIFICATION | NOTIFICATION-001 ~ 003 | 3개 |
| PAYMENT | PAYMENT-001 ~ 008 | 8개 |
| ORDER | ORDER-001 ~ 009 | 9개 |
| STORE-ORDER | STORE-ORDER-001 ~ 009 | 9개 |
| REVIEW | REVIEW-001 ~ 005 | 5개 |

파일 위치: `global/exception/custom/ErrorCode.java`

---

## 9. 테스트 파일 (8개)

| 테스트 파일 | 위치 |
|-----------|------|
| `FinalProjectApplicationTests` | root |
| `PasswordTest` | root |
| `RiderSettlementItemProcessorTest` | settlement/rider/batch |
| `RiderSettlementItemWriterTest` | settlement/rider/batch |
| `RiderWeeklySettlementIntegrationTest` | settlement/rider/batch |
| `SettlementWeekUtilTest` | settlement/rider/util |
| `StoreSettlementBatchIntegrationTest` | settlement/store/batch |
| `StoreSettlementServiceImplTest` | settlement/store/service |

---

## 10. global 패키지 구조

```
global/
├── component/    UserLoader
├── config/       SecurityConfig, SecurityLocalConfig, WebConfig, LocalDataInitializer,
│                 LocalTestUserArgumentResolver, JpaAuditingConfig, CookieUtil,
│                 SmsConfig, AsyncConfig, RetryConfig, RedisConfig, FeignClientConfig,
│                 PostgisDataSourceInitializer
├── domain/       BaseTimeEntity
├── email/        EmailSender, EmailType, EmailContentFactory, PasswordResetEmailFactory
│                 content/ (EmailMessage, HtmlEmailMessage, TextEmailMessage)
├── exception/    BusinessException, ErrorCode, GlobalExceptionHandler
├── jwt/          JwtTokenProvider, JwtProperties
├── response/     ApiResponse<T>
├── security/     JwtAuthenticationFilter, CustomUserDetails, CustomUserDetailsService, UserAuthCache
├── sse/          SseService, SseEmitterRepository, SseEventType
├── storage/      StorageService, S3StorageService, DocumentStorageService,
│                 S3DocumentStorageService, S3DeliveryStorageService, StorageController,
│                 S3Config, StoreImageType
└── util/         GeometryUtil, TemplateRenderer
```

---

## 11. 외부 API 클라이언트

| Client | 역할 |
|--------|------|
| `TossPaymentsClient` | Toss Payments 결제 API |
| `TossFeignConfig` | Toss Basic Auth 설정 |

---

## 12. 회원탈퇴 규칙 (user/withdrawal)

| Rule | 설명 |
|------|------|
| `ActiveSubscriptionWithdrawalRule` | 활성 구독 존재 시 탈퇴 불가 |
| `InProgressOrderWithdrawalRule` | 진행 중 주문 존재 시 탈퇴 불가 |
| `PendingPaymentWithdrawalRule` | 미결제 주문 존재 시 탈퇴 불가 |

---

## 13. 인프라 (Docker Compose)

| Service | Image | Port |
|---------|-------|------|
| PostgreSQL + PostGIS | `postgis/postgis:16-3.4` | 5432 |
| MinIO | `minio/minio` | 9000 / 9001 |
| Redis | `redis:7-alpine` | 6379 |

---

## 14. auth 소셜 로그인 전략

```
auth/social/
├── SocialLoginStrategy (interface)
├── SocialLoginStrategyRegistry
├── KakaoSocialLoginStrategy
└── NaverSocialLoginStrategy
```

---

## 15. 주요 파일 위치 빠른 참조

| 찾는 것 | 경로 |
|--------|------|
| 에러 코드 전체 | `global/exception/custom/ErrorCode.java` |
| API 응답 래퍼 | `global/response/ApiResponse.java` |
| JWT 처리 | `global/jwt/JwtTokenProvider.java` |
| PostGIS 유틸 | `global/util/GeometryUtil.java` |
| 로컬 시드 데이터 | `global/config/LocalDataInitializer.java` |
| Toss 클라이언트 | `payment/client/TossPaymentsClient.java` |
| SSE 서비스 | `global/sse/Service/SseService.java` |
| 정산 배치(마트) | `settlement/store/batch/StoreSettlementBatchConfig.java` |
| 정산 배치(라이더) | `settlement/rider/batch/RiderSettlementBatchConfig.java` |
| 비밀번호 재설정 | `user/service/PasswordResetService.java` |
