package com.example.finalproject.product.dto.response;

import com.example.finalproject.product.domain.Product;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GetProductResponse {

    private Long productId;
    private Long categoryId;
    private String categoryName;
    private String productName;
    private String description;
    private Integer price;
    private Integer salePrice;
    private Integer discountRate;
    private Integer stock;
    private String origin;
    private Boolean isActive;
    private Integer orderCount;
    private String productImageUrl;

    /**
     * 고객에게 보여줄 응답. 재고는 다른 결제가 선점한 수량을 뺀 값이다.
     * 실재고를 보여주면 "3개 남음"을 보고 담았다가 결제에서 튕긴다.
     */
    public static GetProductResponse forCustomer(Product product) {
        return from(product, product.getAvailableStock());
    }

    /**
     * 사장님에게 보여줄 응답. 재고는 실재고다. 선점은 아직 팔린 것이 아니므로
     * 사장님 화면(GetMyProductResponse)과 숫자가 달라지면 안 된다.
     */
    public static GetProductResponse forOwner(Product product) {
        return from(product, product.getStock());
    }

    private static GetProductResponse from(Product product, Integer stock) {
        return GetProductResponse.builder()
                .productId(product.getId())
                .categoryId(product.getProductCategory().getId())
                .categoryName(product.getProductCategory().getCategoryName())
                .productName(product.getProductName())
                .description(product.getDescription())
                .price(product.getPrice())
                .salePrice(product.getSalePrice())
                .discountRate(product.getDiscountRate())
                .stock(stock)
                .origin(product.getOrigin())
                .isActive(product.getIsActive())
                .orderCount(product.getOrderCount())
                .productImageUrl(product.getProductImageUrl())
                .build();
    }
}
