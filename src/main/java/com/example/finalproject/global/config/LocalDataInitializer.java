package com.example.finalproject.global.config;

import com.example.finalproject.delivery.domain.Rider;
import com.example.finalproject.delivery.repository.RiderRepository;
import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.moderation.domain.Approval;
import com.example.finalproject.moderation.enums.ApplicantType;
import com.example.finalproject.moderation.enums.ApprovalStatus;
import com.example.finalproject.moderation.repository.ApprovalRepository;
import com.example.finalproject.coupon.domain.Coupon;
import com.example.finalproject.coupon.repository.CouponRepository;
import com.example.finalproject.order.domain.Cart;
import com.example.finalproject.order.domain.CartProduct;
import com.example.finalproject.order.domain.Order;
import com.example.finalproject.order.enums.OrderStatus;
import com.example.finalproject.order.enums.OrderType;
import com.example.finalproject.order.repository.CartProductRepository;
import com.example.finalproject.order.repository.CartRepository;
import com.example.finalproject.order.repository.OrderRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.domain.ProductCategory;
import com.example.finalproject.product.repository.ProductCategoryRepository;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.domain.StoreBusinessHour;
import com.example.finalproject.store.domain.StoreCategory;
import com.example.finalproject.store.domain.embedded.SettlementAccount;
import com.example.finalproject.store.domain.embedded.StoreAddress;
import com.example.finalproject.store.domain.embedded.SubmittedDocumentInfo;
import com.example.finalproject.store.enums.StoreActiveStatus;
import com.example.finalproject.store.repository.StoreBusinessHourRepository;
import com.example.finalproject.store.repository.StoreCategoryRepository;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.subscription.domain.Subscription;
import com.example.finalproject.subscription.domain.SubscriptionProduct;
import com.example.finalproject.subscription.enums.SubscriptionProductStatus;
import com.example.finalproject.subscription.enums.SubscriptionStatus;
import com.example.finalproject.subscription.repository.SubscriptionProductRepository;
import com.example.finalproject.subscription.repository.SubscriptionRepository;
import com.example.finalproject.payment.domain.Payment;
import com.example.finalproject.payment.domain.PaymentMethod;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.enums.PaymentStatus;
import com.example.finalproject.payment.repository.PaymentMethodRepository;
import com.example.finalproject.payment.repository.PaymentRepository;
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.Role;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.domain.UserRole;
import com.example.finalproject.user.repository.AddressRepository;
import com.example.finalproject.user.repository.RoleRepository;
import com.example.finalproject.user.repository.UserRepository;
import com.example.finalproject.user.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 기존 시드 데이터 주입용. 이제 목데이터(Mock-Data-Loader SQL)로 통일하므로 기본 비활성화.
 * 필요 시 프로필에 local-initializer 를 추가하면 실행됨.
 */
@Slf4j
@Component
@Profile("local-initializer")
@RequiredArgsConstructor
public class LocalDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final PasswordEncoder passwordEncoder;
    private final StoreCategoryRepository storeCategoryRepository;
    private final StoreRepository storeRepository;
    private final StoreBusinessHourRepository storeBusinessHourRepository;
    private final ProductRepository productRepository;
    private final CartRepository cartRepository;
    private final CartProductRepository cartProductRepository;
    private final AddressRepository addressRepository;
    private final PaymentMethodRepository paymentMethodRepository;
    private final CouponRepository couponRepository;
    private final ApprovalRepository approvalRepository;
    private final RiderRepository riderRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionProductRepository subscriptionProductRepository;

    @Override
    @Transactional
    public void run(String... args) {

        // StoreCategory 시드 (입점 신청 시 카테고리 조회용)
        List<String> storeCategories = List.of("마트/슈퍼", "청과물", "정육점", "수산시장", "간식", "베이커리", "반찬가게", "철물/생활", "준비중");
        for (String categoryName : storeCategories) {
            if (storeCategoryRepository.findByCategoryName(categoryName).isEmpty()) {
                storeCategoryRepository.save(StoreCategory.builder().categoryName(categoryName).build());
                log.info("StoreCategory 시드: {}", categoryName);
            }
        }

        // Product Category 초기 데이터
        seedCategory("채소", "https://cdn.example.com/icons/vegetable.png");
        seedCategory("과일", "https://cdn.example.com/icons/fruit.png");
        seedCategory("정육", "https://cdn.example.com/icons/meat.png");
        seedCategory("유제품", "https://cdn.example.com/icons/dairy.png");
        seedCategory("식재료", "https://cdn.example.com/icons/ingredient.png");
        seedCategory("생활용품", "https://cdn.example.com/icons/living.png");

        // ADMIN 역할 생성 (없으면)
        Role adminRole = roleRepository.findByRoleName("ADMIN")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName("ADMIN")
                        .build()));

        // CUSTOMER 역할 생성 (없으면) - 기본 회원 역할
        Role userRole = roleRepository.findByRoleName("CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName("CUSTOMER")
                        .build()));

        roleRepository.findByRoleName("RIDER")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName("RIDER")
                        .build()));

        roleRepository.findByRoleName("STORE")
                .orElseGet(() -> roleRepository.save(Role.builder()
                        .roleName("STORE")
                        .build()));

        // ADMIN 테스트 계정 생성 (없으면)
        String adminEmail = "admin@test.com";
        if (userRepository.findByEmail(adminEmail).isEmpty()) {
            User adminUser = User.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode("admin1234"))
                    .name("관리자")
                    .phone("01000000000")
                    .termsAgreed(true)
                    .privacyAgreed(true)
                    .termsAgreedAt(LocalDateTime.now())
                    .privacyAgreedAt(LocalDateTime.now())
                    .build();
            userRepository.save(adminUser);

            userRoleRepository.save(UserRole.builder()
                    .user(adminUser)
                    .role(adminRole)
                    .build());

            log.info("==============================================");
            log.info("ADMIN 테스트 계정이 생성되었습니다.");
            log.info("Email: {}", adminEmail);
            log.info("Password: admin1234");
            log.info("==============================================");
        }

        // 일반 CUSTOMER 테스트 계정 생성 (없으면) - 프로필 조회 GET /api/users/me 테스트용
        // 전화번호는 목데이터(customer1@test.com의 010-1111-1111)와 겹치지 않게 010-0000-0002 사용 (users_phone_key UNIQUE)
        String userEmail = "user@test.com";
        if (userRepository.findByEmail(userEmail).isEmpty()) {
            User normalUser = User.builder()
                    .email(userEmail)
                    .password(passwordEncoder.encode("user1234"))
                    .name("테스트유저")
                    .phone("010-0000-0002")
                    .termsAgreed(true)
                    .privacyAgreed(true)
                    .termsAgreedAt(LocalDateTime.now())
                    .privacyAgreedAt(LocalDateTime.now())
                    .build();
            userRepository.save(normalUser);

            userRoleRepository.save(UserRole.builder()
                    .user(normalUser)
                    .role(userRole)
                    .build());

            log.info("==============================================");
            log.info("CUSTOMER 테스트 계정이 생성되었습니다. (프로필 조회 GET /api/users/me 테스트용)");
            log.info("Email: {}", userEmail);
            log.info("Password: user1234");
            log.info("프로필: 이름=테스트유저, 연락처=010-0000-0002, 가입일=DB createdAt");
            log.info("==============================================");
        }

        // 결제 더미데이터 (장바구니·주문서·결제 플로우 확인용)
        seedCheckoutDummyData(userRole);

        // 회원탈퇴 테스트더미 user@test.com에 진행중 구독, 결제 대기, 진행중 주문 존재 (탈퇴 불가 검증용)
        seedWithdrawalTestDummyData();

        // 거리 기반 배송비 테스트: 1km / 2km / 3km 이내 테스트마트 3곳 (user@test.com 기본 배송지 기준)
        seedDistanceTestMarts(userRole);

        // 신청 목록 더미데이터 (상점 2건 + 라이더 2건 PENDING)
        seedApprovalListDummyData(userRole);
    }

    // 결제 더미데이터
    // 스토어, 상품, 테스트 유저 장바구니에 상품 담기 (장바구니·주문서·결제 플로우 확인용)
    private void seedCheckoutDummyData(Role userRole) {
        String storeOwnerEmail = "storeowner@test.com";
        Role storeOwnerRole = roleRepository.findByRoleName("STORE").orElseThrow();
        User storeOwner = userRepository.findByEmail(storeOwnerEmail).orElseGet(() -> {
            User owner = User.builder()
                    .email(storeOwnerEmail)
                    .password(passwordEncoder.encode("owner1234"))
                    .name("스토어오너")
                    .phone("01022222222")
                    .termsAgreed(true)
                    .privacyAgreed(true)
                    .termsAgreedAt(LocalDateTime.now())
                    .privacyAgreedAt(LocalDateTime.now())
                    .build();
            userRepository.save(owner);
            userRoleRepository.save(UserRole.builder().user(owner).role(storeOwnerRole).build());
            log.info("결제 더미데이터: 스토어 오너 계정 생성 - {}", storeOwnerEmail);
            return owner;
        });

        Store store = storeRepository.findByOwner(storeOwner).orElseGet(() -> {
            StoreCategory martCategory = storeCategoryRepository.findByCategoryName("마트/슈퍼")
                    .orElseThrow();
            StoreAddress address = StoreAddress.builder()
                    .postalCode("04524")
                    .addressLine1("서울특별시 중구 세종대로 110")
                    .addressLine2("1층")
                    .location(GeometryUtil.createPoint(126.9779, 37.5663))
                    .build();
            SettlementAccount settlement = SettlementAccount.builder()
                    .bankName("테스트은행")
                    .bankAccount("110-123-456789")
                    .accountHolder("스토어오너")
                    .build();
            SubmittedDocumentInfo doc = SubmittedDocumentInfo.builder()
                    .businessOwnerName("스토어오너")
                    .businessNumber("123456789012")
                    .telecomSalesReportNumber("제2024-서울강남-00001")
                    .build();
            Store newStore = Store.builder()
                    .owner(storeOwner)
                    .storeCategory(martCategory)
                    .storeName("동네마켓 테스트마트")
                    .phone("02-1234-5678")
                    .description("결제/장바구니 확인용 더미 스토어")
                    .representativeName("스토어오너")
                    .representativePhone("01022222222")
                    .submittedDocumentInfo(doc)
                    .address(address)
                    .settlementAccount(settlement)
                    .build();
            newStore = storeRepository.save(newStore);
            newStore.approve();
            newStore.setDeliveryAvailable(true);
            seedBusinessHoursAllDaysOpen(newStore);
            log.info("결제 더미데이터: 스토어 생성 - {}", newStore.getStoreName());
            return newStore;
        });

        ProductCategory vegCategory = productCategoryRepository.findByCategoryName("채소")
                .orElseThrow();
        Set<String> dummyProductNames = Set.of(
                "대파 1kg", "양파 2kg", "당근 500g",
                "배추 1통", "깻잎 1단", "청경채 200g", "감자 1kg", "고구마 500g"
        );
        List<String[]> productRows = List.of(
                new String[]{"대파 1kg", "신선한 국내산 대파", "3000", "10", "0"},
                new String[]{"양파 2kg", "노란 양파", "4500", "20", "10"},
                new String[]{"당근 500g", "당근", "2500", "15", "0"},
                new String[]{"배추 1통", "국내산 배추", "4500", "12", "5"},
                new String[]{"깻잎 1단", "찬밤 깻잎", "2000", "30", "0"},
                new String[]{"청경채 200g", "청경채", "1800", "18", "0"},
                new String[]{"감자 1kg", "국내 감자", "3500", "25", "10"},
                new String[]{"고구마 500g", "호박고구마", "3200", "15", "0"}
        );
        for (String[] row : productRows) {
            String name = row[0];
            boolean exists = productRepository.existsByStoreAndProductNameAndDeletedAtIsNull(store, name);
            if (exists) {
                continue;
            }
            Product p = Product.builder()
                    .store(store)
                    .productCategory(vegCategory)
                    .productName(name)
                    .description(row[1])
                    .price(Integer.parseInt(row[2]))
                    .stock(Integer.parseInt(row[3]))
                    .discountRate(row[4].isEmpty() ? null : Integer.parseInt(row[4]))
                    .build();
            p = productRepository.save(p);
            p.updateStatus(true);
            log.info("결제 더미데이터: 상품 생성 - {}", name);
        }

        // 이 스토어의 더미 상품 조회 (방금 생성했거나 기존 DB에 있든 모두 포함)
        List<Product> productsForCart = productRepository.findByStoreAndDeletedAtIsNull(store,
                        org.springframework.data.domain.Pageable.unpaged())
                .getContent()
                .stream()
                .filter(p -> dummyProductNames.contains(p.getProductName()))
                .collect(Collectors.toList());

        User testUser = userRepository.findByEmail("user@test.com").orElse(null);
        if (testUser != null && !productsForCart.isEmpty()) {
            Cart cart = cartRepository.findByUserId(testUser.getId())
                    .orElseGet(() -> cartRepository.save(Cart.create(testUser)));
            int added = 0;
            for (Product product : productsForCart) {
                if (cartProductRepository.findByCartIdAndProductId(cart.getId(), product.getId()).isEmpty()) {
                    // 수량은 상품마다 1~3개로 다양하게
                    int qty = (product.getProductName().length() % 3) + 1;
                    if (qty < 1) {
                        qty = 1;
                    }
                    cartProductRepository.save(CartProduct.builder()
                            .cart(cart)
                            .product(product)
                            .store(store)
                            .quantity(qty)
                            .build());
                    added++;
                }
            }
            if (added > 0) {
                log.info("결제 더미데이터: user@test.com 장바구니에 상품 {}건 담김", added);
            }

            // 결제 더미데이터 user@test.com 배송지·결제수단 1건씩 (주문 생성 API POST /api/orders 호출 시 사용)
            if (addressRepository.findByUserOrderByIsDefaultDesc(testUser).isEmpty()) {
                Address addr = Address.builder()
                        .user(testUser)
                        .contact("01011111111")
                        .addressName("우리 집")
                        .postalCode("04524")
                        .addressLine1("서울특별시 중구 세종대로 110")
                        .addressLine2("1층")
                        .location(GeometryUtil.createPoint(126.9779, 37.5663))
                        .isDefault(true)
                        .build();
                addressRepository.save(addr);
                log.info("결제 더미데이터: user@test.com 배송지 1건 생성");
            }
            // 시드 더미 결제 테스트: 주문/결제 창에서 "결제하기" 시 다음 단계로 진행 가능하도록 결제수단 1건 생성
            if (paymentMethodRepository.findFirstByUserIdAndIsDefaultTrue(testUser.getId()).isEmpty()) {
                PaymentMethod pm = PaymentMethod.builder()
                        .user(testUser)
                        .methodType(PaymentMethodType.CARD)
                        .billingKey("dummy-billing-key-test")
                        .isDefault(true)
                        .build();
                paymentMethodRepository.save(pm);
                log.info("결제 더미데이터: user@test.com 결제수단 1건 생성");
            }
            // 테스트용 쿠폰 더미: 결제창 쿠폰 드롭다운용
            if (couponRepository.findByUserIdOrderByIdAsc(testUser.getId()).isEmpty()) {
                couponRepository.save(Coupon.builder()
                        .name("테스트용 쿠폰")
                        .discountAmount(1000)
                        .user(testUser)
                        .build());
                log.info("결제 더미데이터: user@test.com 쿠폰 [테스트용 쿠폰] 1건 생성");
            }
            // 보유 포인트 더미: 결제창 "현재 보유 포인트" 표시용 (추가 기능 확장 시 적립/차감 연동)
            if (testUser.getPoints() == null || testUser.getPoints() == 0) {
                testUser.setPoints(5000);
                userRepository.save(testUser);
                log.info("결제 더미데이터: user@test.com 보유 포인트 5000원 설정");
            }
        }
    }

    /**
     * 회원탈퇴 테스트더미: user@test.com에 진행중 구독, 결제 대기 상태, 진행중 주문 3가지를 넣어 GET /api/users/me/withdrawal/eligibility 시 탈퇴 불가·사유
     * 반환, DELETE /api/users/me 시 409 검증용.
     */
    private void seedWithdrawalTestDummyData() {
        User testUser = userRepository.findByEmail("user@test.com").orElse(null);
        if (testUser == null) {
            return;
        }
        User storeOwner = userRepository.findByEmail("storeowner@test.com").orElse(null);
        if (storeOwner == null) {
            return;
        }
        Store store = storeRepository.findByOwner(storeOwner).orElse(null);
        if (store == null) {
            return;
        }

        // 회원탈퇴 테스트더미: 배송지 또는 결제수단 없으면 스킵
        List<Address> addresses = addressRepository.findByUserOrderByIsDefaultDesc(testUser);
        Address deliveryAddress = addresses.isEmpty() ? null : addresses.get(0);
        PaymentMethod paymentMethod = paymentMethodRepository.findFirstByUserIdAndIsDefaultTrue(testUser.getId())
                .orElse(null);
        if (deliveryAddress == null || paymentMethod == null) {
            log.warn("회원탈퇴 테스트더미: user@test.com 배송지 또는 결제수단 없음, 스킵");
            return;
        }

        // 회원탈퇴 테스트더미: 진행중 구독용 구독 상품 (ACTIVE)
        List<SubscriptionProduct> storeProducts = subscriptionProductRepository.findByStoreIdOrderByCreatedAtDesc(
                store.getId());
        SubscriptionProduct subProduct = storeProducts.stream()
                .filter(p -> p.getStatus() == SubscriptionProductStatus.ACTIVE)
                .findFirst()
                .orElseGet(() -> {
                    SubscriptionProduct p = SubscriptionProduct.builder()
                            .store(store)
                            .subscriptionProductName("회원탈퇴 테스트더미 - 진행중 구독용")
                            .description("탈퇴 불가 검증용 더미 구독 상품")
                            .price(19_900)
                            .totalDeliveryCount(4)
                            .deliveryCountOfWeek(1)
                            .build();
                    p = subscriptionProductRepository.save(p);
                    log.info("회원탈퇴 테스트더미: 구독 상품 생성 - [진행중 구독용]");
                    return p;
                });

        // 회원탈퇴 테스트더미: 진행중 구독 1건 (ACTIVE) — 탈퇴 시 "진행 중인 구독이 있어 탈퇴할 수 없습니다" 사유용
        long activeSubCount = subscriptionRepository.countByUserIdAndStatusIn(testUser.getId(),
                List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAUSED, SubscriptionStatus.CANCELLATION_PENDING));
        if (activeSubCount == 0) {
            Subscription sub = Subscription.builder()
                    .user(testUser)
                    .store(store)
                    .subscriptionProduct(subProduct)
                    .address(deliveryAddress)
                    .paymentMethod(paymentMethod)
                    .totalAmount(19_900)
                    .startedAt(LocalDateTime.now().minusDays(7))
                    .nextPaymentDate(LocalDate.now().plusDays(7))
                    .deliveryTimeSlot("09:00-12:00")
                    .status(SubscriptionStatus.ACTIVE)
                    .build();
            subscriptionRepository.save(sub);
            log.info("회원탈퇴 테스트더미: 진행중 구독 1건 생성 (ACTIVE) — 탈퇴 불가 사유용");
        }

        // 회원탈퇴 테스트더미: 진행중 주문 1건 (PENDING) + 결제 대기 1건 (PENDING) — 탈퇴 시 주문/결제 사유용
        long inProgressOrderCount = orderRepository.countByUserIdAndStatusIn(testUser.getId(),
                List.of(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.PARTIAL_CANCELLED));
        if (inProgressOrderCount == 0) {
            String orderNumber = "WD-ORDER-" + System.currentTimeMillis();
            Order order = Order.builder()
                    .orderNumber(orderNumber)
                    .user(testUser)
                    .orderType(OrderType.REGULAR)
                    .totalProductPrice(15_000)
                    .totalDeliveryFee(3_000)
                    .finalPrice(18_000)
                    .deliveryAddress(deliveryAddress.getAddressLine1() + " " + deliveryAddress.getAddressLine2())
                    .deliveryLocation(deliveryAddress.getLocation())
                    .deliveryRequest("회원탈퇴 테스트더미")
                    .orderedAt(LocalDateTime.now())
                    .build();
            order = orderRepository.save(order);
            log.info("회원탈퇴 테스트더미: 진행중 주문 1건 생성 (PENDING) — 탈퇴 불가 사유용");

            String pgOrderId = "WD-PG-" + System.currentTimeMillis();
            Payment payment = Payment.builder()
                    .order(order)
                    .paymentStatus(PaymentStatus.PENDING)
                    .paymentMethod(PaymentMethodType.CARD)
                    .amount(order.getFinalPrice())
                    .pgOrderId(pgOrderId)
                    .pgProvider("dummy")
                    .build();
            paymentRepository.save(payment);
            log.info("회원탈퇴 테스트더미: 결제 대기 1건 생성 (PENDING) — 탈퇴 불가 사유용");
        }
    }

    // 신청 목록 더미: 상점 2건 + 라이더 2건 PENDING
    private void seedApprovalListDummyData(Role userRole) {
        String pw = passwordEncoder.encode("password123");
        LocalDateTime now = LocalDateTime.now();

        User storeApp1 = userRepository.findByEmail("storeapp1@dongnae.com").orElseGet(() -> {
            User u = userRepository.save(
                    User.builder().email("storeapp1@dongnae.com").password(pw).name("신청상점1").phone("01080000001")
                            .termsAgreed(true).privacyAgreed(true).termsAgreedAt(now).privacyAgreedAt(now).build());
            userRoleRepository.save(UserRole.builder().user(u).role(userRole).build());
            return u;
        });
        User storeApp2 = userRepository.findByEmail("storeapp2@dongnae.com").orElseGet(() -> {
            User u = userRepository.save(
                    User.builder().email("storeapp2@dongnae.com").password(pw).name("신청상점2").phone("01080000002")
                            .termsAgreed(true).privacyAgreed(true).termsAgreedAt(now).privacyAgreedAt(now).build());
            userRoleRepository.save(UserRole.builder().user(u).role(userRole).build());
            return u;
        });
        User riderApp1 = userRepository.findByEmail("riderapp1@dongnae.com").orElseGet(() -> {
            User u = userRepository.save(
                    User.builder().email("riderapp1@dongnae.com").password(pw).name("신청라이더1").phone("01080000003")
                            .termsAgreed(true).privacyAgreed(true).termsAgreedAt(now).privacyAgreedAt(now).build());
            userRoleRepository.save(UserRole.builder().user(u).role(userRole).build());
            return u;
        });
        User riderApp2 = userRepository.findByEmail("riderapp2@dongnae.com").orElseGet(() -> {
            User u = userRepository.save(
                    User.builder().email("riderapp2@dongnae.com").password(pw).name("신청라이더2").phone("01080000004")
                            .termsAgreed(true).privacyAgreed(true).termsAgreedAt(now).privacyAgreedAt(now).build());
            userRoleRepository.save(UserRole.builder().user(u).role(userRole).build());
            return u;
        });

        if (approvalRepository.findFirstByUserAndApplicantTypeAndStatus(storeApp1, ApplicantType.STORE,
                ApprovalStatus.PENDING).isEmpty()) {
            approvalRepository.save(Approval.builder().user(storeApp1).applicantType(ApplicantType.STORE).build());
        }
        if (approvalRepository.findFirstByUserAndApplicantTypeAndStatus(storeApp2, ApplicantType.STORE,
                ApprovalStatus.PENDING).isEmpty()) {
            approvalRepository.save(Approval.builder().user(storeApp2).applicantType(ApplicantType.STORE).build());
        }
        boolean riderApp1AlreadyApproved = riderRepository.findByUserId(riderApp1.getId())
                .map(rider -> rider.getStatus() == com.example.finalproject.delivery.enums.RiderApprovalStatus.APPROVED)
                .orElse(false);
        if (!riderApp1AlreadyApproved
                && approvalRepository.findFirstByUserAndApplicantTypeAndStatus(riderApp1, ApplicantType.RIDER,
                ApprovalStatus.PENDING).isEmpty()) {
            approvalRepository.save(Approval.builder().user(riderApp1).applicantType(ApplicantType.RIDER).build());
        }
        boolean riderApp2AlreadyApproved = riderRepository.findByUserId(riderApp2.getId())
                .map(rider -> rider.getStatus() == com.example.finalproject.delivery.enums.RiderApprovalStatus.APPROVED)
                .orElse(false);
        if (!riderApp2AlreadyApproved
                && approvalRepository.findFirstByUserAndApplicantTypeAndStatus(riderApp2, ApplicantType.RIDER,
                ApprovalStatus.PENDING).isEmpty()) {
            approvalRepository.save(Approval.builder().user(riderApp2).applicantType(ApplicantType.RIDER).build());
        }

        StoreCategory martCat = storeCategoryRepository.findByCategoryName("마트/슈퍼").orElseThrow();
        StoreCategory meatCat = storeCategoryRepository.findByCategoryName("정육점").orElseThrow();

        if (storeRepository.findByOwner(storeApp1).isEmpty()) {
            Store s = Store.builder()
                    .owner(storeApp1)
                    .storeCategory(martCat)
                    .storeName("테스트마트1")
                    .phone("01090000001")
                    .description("신청 상점 설명1")
                    .representativeName("홍길동")
                    .representativePhone("01090000001")
                    .submittedDocumentInfo(
                            SubmittedDocumentInfo.builder().businessOwnerName("홍길동").businessNumber("111222333444")
                                    .telecomSalesReportNumber("TSR-0001").build())
                    .address(
                            StoreAddress.builder().postalCode("06236").addressLine1("서울시 강남구 테헤란로 1").addressLine2("1층")
                                    .location(GeometryUtil.createPoint(127.0276, 37.4979)).build())
                    .settlementAccount(SettlementAccount.builder().bankName("신한은행").bankAccount("110-123-456789")
                            .accountHolder("홍길동").build())
                    .build();
            s = storeRepository.save(s);
            s.setActiveStatus(StoreActiveStatus.INACTIVE);
            log.info("신청 목록 더미: 상점 신청 1건 생성 - 테스트마트1");
        }
        if (storeRepository.findByOwner(storeApp2).isEmpty()) {
            Store s = Store.builder()
                    .owner(storeApp2)
                    .storeCategory(meatCat)
                    .storeName("테스트마트2")
                    .phone("01090000002")
                    .description("신청 상점 설명2")
                    .representativeName("김철수")
                    .representativePhone("01090000002")
                    .submittedDocumentInfo(
                            SubmittedDocumentInfo.builder().businessOwnerName("김철수").businessNumber("555666777888")
                                    .telecomSalesReportNumber("TSR-0002").build())
                    .address(
                            StoreAddress.builder().postalCode("06611").addressLine1("서울시 서초구 강남대로 2").addressLine2("2층")
                                    .location(GeometryUtil.createPoint(127.0280, 37.4980)).build())
                    .settlementAccount(SettlementAccount.builder().bankName("국민은행").bankAccount("120-987-654321")
                            .accountHolder("김철수").build())
                    .build();
            s = storeRepository.save(s);
            s.setActiveStatus(StoreActiveStatus.INACTIVE);
            log.info("신청 목록 더미: 상점 신청 1건 생성 - 테스트마트2");
        }

        if (riderRepository.findByUserId(riderApp1.getId()).isEmpty()) {
            riderRepository.save(
                    Rider.builder().user(riderApp1).bankName("우리은행").bankAccount("333-444-555555").accountHolder("박라이더")
                            .build());
            log.info("신청 목록 더미: 라이더 신청 1건 생성 - 신청라이더1");
        }
        if (riderRepository.findByUserId(riderApp2.getId()).isEmpty()) {
            riderRepository.save(
                    Rider.builder().user(riderApp2).bankName("하나은행").bankAccount("777-888-999999").accountHolder("최라이더")
                            .build());
            log.info("신청 목록 더미: 라이더 신청 1건 생성 - 신청라이더2");
        }
    }

    /**
     * 거리 기반 배송비 테스트: user@test.com 기본 배송지(127.0276, 37.4979) 기준 - 1km 이내 테스트마트: ~0.5km → 배송비 3,000원 - 2km 이내 테스트마트:
     * ~1.5km → 배송비 4,000원 - 3km 이내 테스트마트: ~2.5km → 배송비 5,000원
     */
    private void seedDistanceTestMarts(Role userRole) {
        // user@test.com 기본 배송지(서울특별시 중구 세종대로 110)와 같은 기준점.
        double baseLon = 126.9779;
        double baseLat = 37.5663;
        // 위도 1도 ≈ 111km → 0.5km ≈ 0.0045, 1.5km ≈ 0.0135, 2.5km ≈ 0.0225
        List<Object[]> marts = List.of(
                new Object[]{"mart1km@test.com", "1km이내 테스트마트", baseLon, baseLat + 0.0045, "거리 0.5km → 배송비 3,000원",
                        "333456789001", "제2024-서울강남-10101", "01033333001"},
                new Object[]{"mart2km@test.com", "2km이내 테스트마트", baseLon, baseLat + 0.0135, "거리 1.5km → 배송비 4,000원",
                        "333456789002", "제2024-서울강남-10102", "01033333002"},
                new Object[]{"mart3km@test.com", "3km이내 테스트마트", baseLon, baseLat + 0.0225, "거리 2.5km → 배송비 5,000원",
                        "333456789003", "제2024-서울강남-10103", "01033333003"}
        );
        StoreCategory martCategory = storeCategoryRepository.findByCategoryName("마트/슈퍼").orElseThrow();
        ProductCategory vegCategory = productCategoryRepository.findByCategoryName("채소").orElseThrow();
        for (Object[] row : marts) {
            String ownerEmail = (String) row[0];
            String storeName = (String) row[1];
            Double lon = (Double) row[2];
            Double lat = (Double) row[3];
            String desc = (String) row[4];
            String businessNumber = (String) row[5];
            String telecomNumber = (String) row[6];
            String ownerPhone = (String) row[7];
            User owner = userRepository.findByEmail(ownerEmail).orElseGet(() -> {
                User u = User.builder()
                        .email(ownerEmail)
                        .password(passwordEncoder.encode("owner1234"))
                        .name("오너-" + storeName)
                        .phone(ownerPhone)
                        .termsAgreed(true)
                        .privacyAgreed(true)
                        .termsAgreedAt(LocalDateTime.now())
                        .privacyAgreedAt(LocalDateTime.now())
                        .build();
                userRepository.save(u);
                userRoleRepository.save(UserRole.builder().user(u).role(userRole).build());
                return u;
            });
            Store store = storeRepository.findByOwner(owner).orElse(null);
            if (store == null) {
                StoreAddress address = StoreAddress.builder()
                        .postalCode("06134")
                        .addressLine1(storeName + " 주소")
                        .addressLine2("1층")
                        .location(GeometryUtil.createPoint(lon, lat))
                        .build();
                store = Store.builder()
                        .owner(owner)
                        .storeCategory(martCategory)
                        .storeName(storeName)
                        .phone("02-1234-5678")
                        .description(desc)
                        .representativeName("테스트오너")
                        .representativePhone(ownerPhone)
                        .submittedDocumentInfo(SubmittedDocumentInfo.builder()
                                .businessOwnerName("테스트오너")
                                .businessNumber(businessNumber)
                                .telecomSalesReportNumber(telecomNumber)
                                .build())
                        .address(address)
                        .settlementAccount(SettlementAccount.builder()
                                .bankName("테스트은행")
                                .bankAccount("110-123-456789")
                                .accountHolder("테스트오너")
                                .build())
                        .build();
                store = storeRepository.save(store);
                store.approve();
                store.setDeliveryAvailable(true);
                store.setActiveStatus(StoreActiveStatus.ACTIVE);
                seedBusinessHoursAllDaysOpen(store);
                log.info("거리 테스트마트 시드: {} (배송비 구간 테스트용)", storeName);
                for (String productName : List.of("테스트 상품 A", "테스트 상품 B")) {
                    if (!productRepository.existsByStoreAndProductNameAndDeletedAtIsNull(store, productName)) {
                        Product p = Product.builder()
                                .store(store)
                                .productCategory(vegCategory)
                                .productName(productName)
                                .description(storeName + " " + productName)
                                .price(1000)
                                .stock(100)
                                .build();
                        p = productRepository.save(p);
                        p.updateStatus(true);
                    }
                }
            }
            // user@test.com 장바구니에 이 마트 상품 1건 담기 (거리별 배송비 확인용)
            User testUser = userRepository.findByEmail("user@test.com").orElse(null);
            if (testUser != null && store != null) {
                List<Product> products = productRepository.findByStoreAndDeletedAtIsNull(store,
                        org.springframework.data.domain.Pageable.unpaged()).getContent();
                if (!products.isEmpty()) {
                    Cart cart = cartRepository.findByUserId(testUser.getId())
                            .orElseGet(() -> cartRepository.save(Cart.create(testUser)));
                    Product first = products.get(0);
                    if (cartProductRepository.findByCartIdAndProductId(cart.getId(), first.getId()).isEmpty()) {
                        cartProductRepository.save(CartProduct.builder()
                                .cart(cart)
                                .product(first)
                                .store(store)
                                .quantity(1)
                                .build());
                        log.info("거리 테스트마트 시드: user@test.com 장바구니에 [{}] 1건 담김 (배송비 확인용)", storeName);
                    }
                }
            }
        }
    }

    /**
     * 매장 검색(StoreRepositoryImpl.notClosedToday)이 "오늘 요일에 isClosed=false인
     * 영업시간 행이 있는 매장만" 보여준다. 이 행이 하나도 없으면 그 매장은 위치·배달가능
     * 여부와 무관하게 검색 결과에서 항상 빠진다 — 그래서 더미 매장마다 요일 7개를 전부 채운다.
     */
    private void seedBusinessHoursAllDaysOpen(Store store) {
        for (short day = 0; day < 7; day++) {
            if (storeBusinessHourRepository.findByStoreAndDayOfWeek(store, day).isEmpty()) {
                storeBusinessHourRepository.save(StoreBusinessHour.builder()
                        .store(store)
                        .dayOfWeek(day)
                        .openTime(java.time.LocalTime.of(9, 0))
                        .closeTime(java.time.LocalTime.of(22, 0))
                        .isClosed(false)
                        .build());
            }
        }
    }

    private void seedCategory(String name, String iconUrl) {
        if (productCategoryRepository.findByCategoryName(name).isEmpty()) {
            productCategoryRepository.save(
                    ProductCategory.builder()
                            .categoryName(name)
                            .iconUrl(iconUrl)
                            .build()
            );
        }
    }
}
