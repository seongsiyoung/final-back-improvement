package com.example.finalproject.testsupport;

import com.example.finalproject.global.util.GeometryUtil;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.domain.ProductCategory;
import com.example.finalproject.product.repository.ProductCategoryRepository;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.domain.StoreCategory;
import com.example.finalproject.store.domain.embedded.SettlementAccount;
import com.example.finalproject.store.domain.embedded.StoreAddress;
import com.example.finalproject.store.domain.embedded.SubmittedDocumentInfo;
import com.example.finalproject.store.repository.StoreCategoryRepository;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.user.domain.Address;
import com.example.finalproject.user.domain.Role;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.domain.UserRole;
import com.example.finalproject.user.repository.AddressRepository;
import com.example.finalproject.user.repository.RoleRepository;
import com.example.finalproject.user.repository.UserRepository;
import com.example.finalproject.user.repository.UserRoleRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 부하 테스트 전용 시더. LocalDataInitializer(local-initializer 프로파일)와 무관한 별도 컴포넌트다. */
@Component
@RequiredArgsConstructor
public class LoadTestDataSeeder {

    private static final double STORE_LON = 127.0276;
    private static final double STORE_LAT = 37.4979;

    private final StoreCategoryRepository storeCategoryRepository;
    private final StoreRepository storeRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    public User seedUserWithAddress(String email, String rawPassword) {
        Role customerRole = roleRepository.findByRoleName("CUSTOMER")
                .orElseGet(() -> roleRepository.save(Role.builder().roleName("CUSTOMER").build()));

        User user = userRepository.findByEmail(email).orElseGet(() -> userRepository.save(
                User.builder()
                        .email(email)
                        .password(passwordEncoder.encode(rawPassword))
                        .name("부하테스트유저")
                        .phone("010" + String.format("%08d", (int) (Math.random() * 100_000_000)))
                        .termsAgreed(true)
                        .privacyAgreed(true)
                        .termsAgreedAt(LocalDateTime.now())
                        .privacyAgreedAt(LocalDateTime.now())
                        .build()));

        if (!userRoleRepository.existsByUserIdAndRoleId(user.getId(), customerRole.getId())) {
            userRoleRepository.save(UserRole.builder().user(user).role(customerRole).build());
        }

        if (addressRepository.findByUserOrderByIsDefaultDesc(user).isEmpty()) {
            addressRepository.save(Address.builder()
                    .user(user)
                    .contact(user.getPhone())
                    .addressName("우리 집")
                    .postalCode("06134")
                    .addressLine1("서울시 강남구 테헤란로 123")
                    .addressLine2("1층")
                    .location(GeometryUtil.createPoint(STORE_LON, STORE_LAT))
                    .isDefault(true)
                    .build());
        }

        return user;
    }

    public Store seedStoreWithProducts(int productCount, int stockPerProduct) {
        return seedStoreWithProducts("load-test-store-owner@test.com", productCount, stockPerProduct);
    }

    /**
     * 오너 이메일로 매장을 구분한다. 기본 오버로드는 전역 매장 하나를 재사용하므로,
     * 한 주문에 매장이 둘인 상황을 만들려면 다른 오너를 줘야 한다.
     */
    public Store seedStoreWithProducts(String ownerEmail, int productCount, int stockPerProduct) {
        StoreCategory storeCategory = storeCategoryRepository.findByCategoryName("마트/슈퍼")
                .orElseGet(() -> storeCategoryRepository.save(StoreCategory.builder().categoryName("마트/슈퍼").build()));

        ProductCategory productCategory = productCategoryRepository.findByCategoryName("채소")
                .orElseGet(() -> productCategoryRepository.save(
                        ProductCategory.builder().categoryName("채소").iconUrl(null).build()));

        User owner = userRepository.findByEmail(ownerEmail).orElseGet(() ->
                userRepository.save(User.builder()
                        .email(ownerEmail)
                        .password(passwordEncoder.encode("owner1234!"))
                        .name("부하테스트오너")
                        .phone("0109999" + String.format("%04d", Math.abs(ownerEmail.hashCode() % 10000)))
                        .termsAgreed(true)
                        .privacyAgreed(true)
                        .termsAgreedAt(LocalDateTime.now())
                        .privacyAgreedAt(LocalDateTime.now())
                        .build()));

        Store store = storeRepository.findByOwner(owner).orElseGet(() -> {
            Store newStore = storeRepository.save(Store.builder()
                    .owner(owner)
                    .storeCategory(storeCategory)
                    .storeName("부하테스트마트-" + ownerEmail)
                    .phone("02-0000-0000")
                    .description("k6 부하 테스트 전용 스토어")
                    .representativeName("부하테스트오너")
                    .representativePhone("01099999999")
                    .submittedDocumentInfo(SubmittedDocumentInfo.builder()
                            .businessOwnerName("부하테스트오너")
                            .businessNumber(String.valueOf(Math.abs(ownerEmail.hashCode() % 1000000000L) + 100000000L))
                            .telecomSalesReportNumber("제2026-부하-" + Math.abs(ownerEmail.hashCode() % 100000))
                            .build())
                    .address(StoreAddress.builder()
                            .postalCode("06134")
                            .addressLine1("서울시 강남구 테스트로 123")
                            .addressLine2("1층")
                            .location(GeometryUtil.createPoint(STORE_LON, STORE_LAT))
                            .build())
                    .settlementAccount(SettlementAccount.builder()
                            .bankName("테스트은행")
                            .bankAccount("110-000-000000")
                            .accountHolder("부하테스트오너")
                            .build())
                    .build());
            newStore.approve();
            return storeRepository.save(newStore);
        });

        for (int i = 0; i < productCount; i++) {
            String name = "부하테스트상품-" + i;
            if (productRepository.existsByStoreAndProductNameAndDeletedAtIsNull(store, name)) {
                continue;
            }
            Product product = productRepository.save(Product.builder()
                    .store(store)
                    .productCategory(productCategory)
                    .productName(name)
                    .description("k6 부하 테스트 전용 상품")
                    .price(3000)
                    .stock(stockPerProduct)
                    .build());
            product.updateStatus(true);
            productRepository.save(product);
        }

        return store;
    }
}
