package com.example.finalproject.product.service;

import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.product.domain.ProductCategory;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.enums.ProductSortType;
import com.example.finalproject.product.domain.ProductStockHistory;
import com.example.finalproject.product.domain.StockEventType;
import com.example.finalproject.product.dto.request.PatchProductRequest;
import com.example.finalproject.product.dto.request.PatchProductStatusRequest;
import com.example.finalproject.product.dto.request.PostProductRequest;
import com.example.finalproject.product.dto.request.StockAdjustRequest;
import com.example.finalproject.product.dto.response.CanEditProductResponse;
import com.example.finalproject.product.dto.response.GetCategoryResponse;
import com.example.finalproject.product.dto.response.GetMyProductResponse;
import com.example.finalproject.product.dto.response.GetProductResponse;
import com.example.finalproject.product.dto.response.GetProductStatsResponse;
import com.example.finalproject.product.dto.response.GetStockHistoryResponse;
import com.example.finalproject.product.dto.response.PostProductResponse;
import com.example.finalproject.product.dto.response.StockAdjustResponse;
import com.example.finalproject.product.repository.ProductCategoryRepository;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.product.repository.ProductStockHistoryRepository;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.store.domain.StoreBusinessHour;
import com.example.finalproject.store.repository.StoreBusinessHourRepository;
import com.example.finalproject.store.repository.StoreRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductCategoryRepository productCategoryRepository;
    private final StoreRepository storeRepository;
    private final UserRepository userRepository;
    private final StoreBusinessHourRepository storeBusinessHourRepository;
    private final ProductStockHistoryRepository productStockHistoryRepository;

    public PostProductResponse registerProduct(String userName, PostProductRequest request) {

        User user = findUserByUserName(userName);

        log.info("상품 등록 신청. userId ={}, categoryId = {} ",  user.getId(), request.getCategoryId());
        Store store = findStoreByUser(user);

        // 동일 상품명 중복 체크
        if (productRepository.existsByStoreAndProductNameAndDeletedAtIsNull(store, request.getProductName())) {
            throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_NAME);
        }

        ProductCategory productCategory = productCategoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));

        Product product = createProduct(request, store, productCategory);

        Product savedProduct = productRepository.save(product);

        log.info("상품 등록 완료. productId={}, storeId={}", savedProduct.getId(), store.getId());

        return PostProductResponse.of(savedProduct.getId(), savedProduct.getProductName(), savedProduct.getPrice(), savedProduct.getStock());
    }
    
    //상품 정보 조회
    @Transactional(readOnly = true)
    public GetProductResponse getProduct(Long productId) {
        Product product = findActiveProduct(productId);

        return GetProductResponse.forCustomer(product);
    }

    /**
     * 고객용: 마트 상세 메뉴 탭에서 보여줄 일반 상품 목록 (삭제 안 됨, 판매 중인 것만).
     * @param sort null이면 추천순(상품명 오름차순) 적용
     */
    @Transactional(readOnly = true)
    public List<GetProductResponse> getProductsByStoreIdForCustomer(Long storeId, ProductSortType sort) {
        if (storeId == null || !storeRepository.existsById(storeId)) {
            return List.of();
        }
        ProductSortType effectiveSort = sort != null ? sort : ProductSortType.RECOMMENDED;
        Sort order = toSort(effectiveSort);
        Pageable pageable = PageRequest.of(0, 1000, order);
        List<Product> products = productRepository.findByStore_IdAndDeletedAtIsNullAndIsActiveTrue(storeId, pageable);
        return products.stream().map(GetProductResponse::forCustomer).toList();
    }

    private static Sort toSort(ProductSortType sortType) {
        return switch (sortType) {
            case NEWEST -> Sort.by(Sort.Direction.DESC, "createdAt");
            case SALES -> Sort.by(Sort.Direction.DESC, "orderCount");
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "price");
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "price");
            default -> Sort.by(Sort.Direction.ASC, "productName"); // RECOMMENDED
        };
    }

    @Transactional(readOnly = true)
    public Page<GetMyProductResponse> getMyProducts(String userName, Pageable pageable) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        return productRepository.findByStoreAndDeletedAtIsNull(store, pageable)
                .map(GetMyProductResponse::from);
    }

    public GetProductResponse updateProduct(String userName, Long productId, PatchProductRequest request) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        //운영 시간 이전,이후 요청인지 확인
        validateNotDuringBusinessHours(store);

        Product product = findActiveProduct(productId);

        validateProductOwner(store, product);

        // 제품명 변경 시 동일 스토어 내 중복 체크 (수정 대상 상품 제외)
        if (request.getProductName() != null && !request.getProductName().isBlank()) {
            if (productRepository.existsByStoreAndProductNameAndDeletedAtIsNullAndIdNot(store, request.getProductName(), productId)) {
                throw new BusinessException(ErrorCode.DUPLICATE_PRODUCT_NAME);
            }
        }

        if (request.getCategoryId() != null) {
            ProductCategory productCategory = productCategoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.CATEGORY_NOT_FOUND));
            product.updateCategory(productCategory);
        }

        product.update(
                request.getProductName(),
                request.getDescription(),
                request.getPrice(),
                request.getDiscountRate(),
                request.getOrigin(),
                request.getProductImageUrl()
        );

        log.info("상품 수정 완료. productId={}", productId);

        return GetProductResponse.forOwner(product);
    }

    public void deleteProduct(String userName, Long productId) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        validateNotDuringBusinessHours(store);

        Product product = findActiveProduct(productId);

        validateProductOwner(store, product);

        product.delete();

        log.info("상품 삭제 완료. productId={}", productId);
    }

    public GetProductResponse updateProductStatus(String userName, Long productId, PatchProductStatusRequest request) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        Product product = findActiveProduct(productId);

        validateProductOwner(store, product);

        product.updateStatus(request.getIsActive());

        log.info("상품 활성화 상태 변경 완료. productId={}, isActive={}", productId, request.getIsActive());

        return GetProductResponse.forOwner(product);
    }

    public StockAdjustResponse stockIn(String userName, Long productId, StockAdjustRequest request) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);
        Product product = findActiveProduct(productId);

        validateProductOwner(store, product);

        product.increaseStock(request.getQuantity());

        ProductStockHistory history = ProductStockHistory.createInHistory(
                product, request.getQuantity(), product.getStock());
        productStockHistoryRepository.save(history);

        log.info("상품 입고 완료. productId={}, quantity={}, stockAfter={}",
                productId, request.getQuantity(), product.getStock());

        return StockAdjustResponse.of(product.getId(), product.getProductName(),
                StockEventType.IN, request.getQuantity(), product.getStock());
    }

    public StockAdjustResponse stockOut(String userName, Long productId, StockAdjustRequest request) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        // 선점이 걸린 수량은 이미 팔린 것과 같다. 락 없이 stock 만 보고 빼면 stock 이 reserved
        // 아래로 내려가고, 그 선점이 승인으로 확정될 때 stock 이 음수가 된다.
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (product.isDeleted()) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        validateProductOwner(store, product);

        if (product.getAvailableStock() < request.getQuantity()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }

        product.decreaseStock(request.getQuantity());

        ProductStockHistory history = ProductStockHistory.createOutHistory(
                product, request.getQuantity(), product.getStock());
        productStockHistoryRepository.save(history);

        log.info("상품 출고 완료. productId={}, quantity={}, stockAfter={}",
                productId, request.getQuantity(), product.getStock());

        return StockAdjustResponse.of(product.getId(), product.getProductName(),
                StockEventType.OUT, request.getQuantity(), product.getStock());
    }

    @Transactional(readOnly = true)
    public GetProductStatsResponse getProductStats(String userName) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        Long totalCount = productRepository.countByStoreAndDeletedAtIsNull(store);
        Long activeCount = productRepository.countByStoreAndIsActiveAndDeletedAtIsNull(store, true);
        Long inactiveCount = productRepository.countByStoreAndIsActiveAndDeletedAtIsNull(store, false);

        //ex) 2026-01-03T00:00
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Long todayInCount = productStockHistoryRepository.countByStoreAndEventTypeAndCreatedAtAfter(store, StockEventType.IN, startOfDay);
        Long todayOutCount = productStockHistoryRepository.countByStoreAndEventTypeAndCreatedAtAfter(store, StockEventType.OUT, startOfDay);

        return GetProductStatsResponse.of(totalCount, activeCount, inactiveCount, todayInCount, todayOutCount);
    }

    @Transactional(readOnly = true)
    public Page<GetStockHistoryResponse> getStockHistories(String userName, Pageable pageable) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        return productStockHistoryRepository.findByStore(store, pageable)
                .map(GetStockHistoryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<GetCategoryResponse> getCategories() {
        return productCategoryRepository.findAll().stream()
                .map(GetCategoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public CanEditProductResponse canEditProduct(String userName) {

        User user = findUserByUserName(userName);
        Store store = findStoreByUser(user);

        return checkBusinessHours(store);
    }

    private Product createProduct(PostProductRequest request, Store store, ProductCategory productCategory) {
        return Product.builder()
                .store(store)
                .productCategory(productCategory)
                .productName(request.getProductName())
                .description(request.getDescription())
                .price(request.getPrice())
                .discountRate(request.getDiscountRate())
                .stock(0)
                .origin(request.getOrigin())
                .productImageUrl(request.getProductImageUrl())
                .build();
    }

    private Product findActiveProduct(Long productId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        return product;
    }

    private Store findStoreByUser(User user) {
        Store store = storeRepository.findByOwner(user)
                .orElseThrow(() -> new BusinessException(ErrorCode.STORE_NOT_FOUND));
        return store;
    }

    private User findUserByUserName(String userName){
        User user = userRepository.findByEmail(userName).orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        return user;
    }

    //상품 수정,삭제 가능 여부 확인
    private void validateNotDuringBusinessHours(Store store) {
        CanEditProductResponse result = checkBusinessHours(store);
        if (!result.getCanEdit()) {
            throw new BusinessException(ErrorCode.PRODUCT_CHANGE_NOT_ALLOWED_DURING_BUSINESS_HOURS);
        }
    }

    //유저아이디 기반의 store 정보와 등록된 product의 store 정보가 일치하는지 확인
    private void validateProductOwner(Store store, Product product) {
        if (!product.getStore().getId().equals(store.getId())) {
            throw new BusinessException(ErrorCode.PRODUCT_NOT_OWNED);
        }
    }

    //상품 수정,삭제 가능 조건 체크
    private CanEditProductResponse checkBusinessHours(Store store) {
        LocalTime now = LocalTime.now();
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        Short dayOfWeek = (short) (today.getValue() % 7);

        Optional<StoreBusinessHour> businessHourOpt = storeBusinessHourRepository.findByStoreAndDayOfWeek(store, dayOfWeek);

        if (businessHourOpt.isEmpty()) {
            return CanEditProductResponse.of(true, "운영 시간 정보가 없어 수정, 삭제 가능합니다.");
        }

        StoreBusinessHour businessHour = businessHourOpt.get();

        if (businessHour.getIsClosed()) {
            return CanEditProductResponse.of(true, "휴무일이므로 수정, 삭제 가능합니다.");
        }

        LocalTime openTime = businessHour.getOpenTime();
        LocalTime closeTime = businessHour.getCloseTime();

        if (openTime == null || closeTime == null) {
            return CanEditProductResponse.of(true, "운영 시간 정보가 없어 수정, 삭제 가능합니다.");
        }

        boolean isDuringBusinessHours;
        if (closeTime.isAfter(openTime)) {
            isDuringBusinessHours = !now.isBefore(openTime) && now.isBefore(closeTime);
        } else {
            isDuringBusinessHours = !now.isBefore(openTime) || now.isBefore(closeTime);
        }

        if (isDuringBusinessHours) {
            return CanEditProductResponse.of(false, "운영 시간에는 상품 정보를 수정, 삭제 할 수 없습니다.");
        }

        return CanEditProductResponse.of(true, "운영 시간 외이므로 수정, 삭제 가능합니다.");
    }

}
