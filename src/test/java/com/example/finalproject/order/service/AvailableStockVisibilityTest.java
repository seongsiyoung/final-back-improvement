package com.example.finalproject.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.finalproject.order.dto.request.PostCartAddRequest;
import com.example.finalproject.payment.dto.request.PostPaymentPrepareRequest;
import com.example.finalproject.payment.enums.PaymentMethodType;
import com.example.finalproject.payment.service.PaymentService;
import com.example.finalproject.product.dto.request.PatchProductStatusRequest;
import com.example.finalproject.product.dto.response.GetMyProductResponse;
import com.example.finalproject.product.dto.response.GetProductResponse;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.product.service.ProductService;
import com.example.finalproject.store.domain.Store;
import com.example.finalproject.testsupport.IntegrationTestSupport;
import com.example.finalproject.testsupport.LoadTestDataSeeder;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/** 다른 결제가 선점한 수량이 고객 쪽 검증과 표시에서 빠지는지 고정한다. */
class AvailableStockVisibilityTest extends IntegrationTestSupport {

    @Autowired private CartService cartService;
    @Autowired private PaymentService paymentService;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductService productService;
    @Autowired private LoadTestDataSeeder seeder;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("상품 상세는 선점을 뺀 재고를 보여준다")
    void productDetail_showsAvailableStock() {
        Long productId = seedProduct(10);
        reserve(productId, 4);

        GetProductResponse response = productService.getProduct(productId);

        assertThat(response.getStock())
                .as("실재고를 보여주면 10개 남았다고 믿고 담았다가 결제에서 튕긴다")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("사장님 상품 수정 응답은 실재고를 보여준다")
    void ownerUpdateResponse_showsRealStock() {
        Long productId = seedProduct(10);
        reserve(productId, 4);
        String owner = ownerEmailOf(productId);

        PatchProductStatusRequest request = new PatchProductStatusRequest();
        ReflectionTestUtils.setField(request, "isActive", true);
        GetProductResponse response = productService.updateProductStatus(owner, productId, request);

        assertThat(response.getStock())
                .as("사장님 재고 화면과 숫자가 달라지면 출고한 적 없는 재고가 줄어든 것처럼 보인다")
                .isEqualTo(10);
    }

    @Test
    @DisplayName("사장님 재고 관리 화면은 실재고를 보여준다")
    void ownerProductList_showsRealStock() {
        Long productId = seedProduct(10);
        reserve(productId, 4);
        // GetMyProductResponse.from 은 카테고리 LAZY 연관을 탄다. 실제 조회 경로와 같게
        // 트랜잭션 안에서 만든다.
        Integer shownStock = transactionTemplate.execute(status ->
                GetMyProductResponse.from(productRepository.findById(productId).orElseThrow()).getStock());

        assertThat(shownStock).isEqualTo(10);
    }

    @Test
    @DisplayName("장바구니는 선점 때문에 담기를 막지 않는다")
    void addToCart_isNotBlockedByOtherReservations() {
        Long productId = seedProduct(3);
        reserve(productId, 3);
        String buyer = newBuyer("cart-not-blocked");

        cartService.addToCart(buyer, cartRequest(productId, 1));

        assertThat(reservedOf(productId))
                .as("담기는 선점을 만들지 않는다. 최종 판단은 prepare() 가 한다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("장바구니 표시 재고는 실재고다")
    void cartItem_showsRealStock() {
        Long productId = seedProduct(10);
        reserve(productId, 7);
        String buyer = newBuyer("cart-display");
        cartService.addToCart(buyer, cartRequest(productId, 1));

        Integer shownStock = cartService.getMyCart(buyer).getStores().stream()
                .flatMap(store -> store.getItems().stream())
                .filter(item -> item.getProductId().equals(productId))
                .findFirst().orElseThrow()
                .getStock();

        assertThat(shownStock)
                .as("가용재고를 주면 프런트가 그 값으로 결제 버튼을 막아 자기 선점에 자기가 갇힌다")
                .isEqualTo(10);
    }

    private void reserve(Long productId, int quantity) {
        String email = newBuyer("holder-" + quantity);
        PostPaymentPrepareRequest request = new PostPaymentPrepareRequest();
        ReflectionTestUtils.setField(request, "productQuantities", Map.of(productId, quantity));
        ReflectionTestUtils.setField(request, "paymentMethod", PaymentMethodType.CARD);
        ReflectionTestUtils.setField(request, "deliveryAddress", "서울시 강남구 테헤란로 123");
        paymentService.prepare(email, request);
    }

    private PostCartAddRequest cartRequest(Long productId, int quantity) {
        PostCartAddRequest request = new PostCartAddRequest();
        ReflectionTestUtils.setField(request, "productId", productId);
        ReflectionTestUtils.setField(request, "quantity", quantity);
        return request;
    }

    private Long seedProduct(int stock) {
        Store store = seeder.seedStoreWithProducts(
                "available-stock-" + System.nanoTime() + "@test.com", 1, stock);
        return productRepository.findByStoreAndDeletedAtIsNull(store, Pageable.unpaged())
                .getContent().get(0).getId();
    }

    private String ownerEmailOf(Long productId) {
        return jdbcTemplate.queryForObject(
                "select u.email from products p join stores s on s.id = p.store_id "
                        + "join users u on u.id = s.owner_id where p.id = ?",
                String.class, productId);
    }

    private String newBuyer(String prefix) {
        String email = "avail-" + prefix + "-" + System.nanoTime() + "@test.com";
        seeder.seedUserWithAddress(email, "buyer1234!");
        return email;
    }

    private int stockOf(Long productId) {
        return jdbcTemplate.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private int reservedOf(Long productId) {
        return jdbcTemplate.queryForObject("select reserved from products where id = ?", Integer.class, productId);
    }
}
