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

    public static GetProductResponse forCustomer(Product product) {
        return from(product, product.getAvailableStock());
    }

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
