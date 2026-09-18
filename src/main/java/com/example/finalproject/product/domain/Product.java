package com.example.finalproject.product.domain;

import com.example.finalproject.global.domain.BaseTimeEntity;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.store.domain.Store;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_products_store"))
    @OnDelete(action = OnDeleteAction.RESTRICT)
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_products_category"))
    @OnDelete(action = OnDeleteAction.RESTRICT)
    private ProductCategory productCategory;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 12, scale = 2)
    private Integer price;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private Integer salePrice;

    @Column(name = "discount_rate")
    private Integer discountRate;

    @Column(nullable = false)
    private Integer stock = 0;

    // 결제 승인 전 선점 수량
    @Column(nullable = false, columnDefinition = "integer not null default 0")
    private Integer reserved = 0;

    @Column(length = 100)
    private String origin;

    //상품 활성화 여부 -> on/off 버튼이 존재함.
    @Column(name = "is_active", nullable = false)
    private Boolean isActive = false;

    @Column(name = "order_count", nullable = false)
    private Integer orderCount = 0;

    @Column(name = "product_image_url", length = 500)
    private String productImageUrl;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Builder
    public Product(Store store, ProductCategory productCategory, String productName,
                   String description, Integer price,
                   Integer discountRate, Integer stock, String origin, String productImageUrl) {

        validateProductName(productName);
        validatePrice(price);
        //있으면 체크, 없으면 체크하지 않는다.
        if (discountRate != null) {
            validateDiscountRate(discountRate);
        }
        if (origin != null) {
            validateOrigin(origin);
        }
        if (productImageUrl != null) {
            validateProductImageUrl(productImageUrl);
        }

        this.store = store;
        this.productCategory = productCategory;
        this.productName = productName;
        this.description = description;
        this.price = price;
        this.discountRate = discountRate;
        this.salePrice = calculateSalePrice(price, discountRate);
        this.stock = stock != null ? stock : 0;
        this.reserved = 0;
        this.origin = origin;
        this.productImageUrl = productImageUrl;
    }

    private static Integer calculateSalePrice(Integer price, Integer discountRate) {
        if (price == null || discountRate == null || discountRate == 0) {
            return null;
        }
        return price * (100 - discountRate) / 100;
    }

    public void updateCategory(ProductCategory productCategory) {
        this.productCategory = productCategory;
    }

    public void updateStatus(Boolean isActive) {
        this.isActive = isActive;
    }

    public void delete() {
        this.isActive = false;
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return this.deletedAt != null;
    }

    public void update(String productName, String description, Integer price,
                       Integer discountRate, String origin, String productImageUrl) {

        if (productName != null) {
            validateProductName(productName);
            this.productName = productName;
        }
        if (description != null) {
            this.description = description;
        }
        if (price != null) {
            if (price < 1) {
                throw new BusinessException(ErrorCode.INVALID_PRICE);
            }
            this.price = price;
        }
        if (discountRate != null) {
            if (discountRate < 0 || discountRate > 99) {
                throw new BusinessException(ErrorCode.INVALID_DISCOUNT_RATE);
            }
            this.discountRate = discountRate;
        }
        if (origin != null) {
            validateOrigin(origin);
            this.origin = origin;
        }
        if (productImageUrl != null) {
            validateProductImageUrl(productImageUrl);
            this.productImageUrl = productImageUrl;
        }

        // price 또는 discountRate가 변경되면 salePrice 재계산
        if (price != null || discountRate != null) {
            this.salePrice = calculateSalePrice(this.price, this.discountRate);
        }
    }

    public void increaseStock(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        this.stock += quantity;
    }

    public void decreaseStock(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        if (this.stock < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        this.stock -= quantity;

        //판매 중지 상태로 전환
        if (this.stock == 0) {
            this.isActive = false;
        }

    }

    public int getAvailableStock() {
        return this.stock - this.reserved;
    }

    public void reserve(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        if (getAvailableStock() < quantity) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }
        this.reserved += quantity;
    }

    public void releaseReservation(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        if (this.reserved < quantity) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        this.reserved -= quantity;
    }

    public void confirmReservation(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        if (this.reserved < quantity) {
            throw new BusinessException(ErrorCode.INVALID_STOCK_QUANTITY);
        }
        this.reserved -= quantity;
        this.stock -= quantity;

        //판매 중지 상태로 전환
        if (this.stock == 0) {
            this.isActive = false;
        }
    }

    private void validateProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_NAME);
        }
        if (productName.length() > 200) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_NAME);
        }
    }

    private void validatePrice(Integer price) {
        if (price == null || price < 1) {
            throw new BusinessException(ErrorCode.INVALID_PRICE);
        }
    }

    private void validateDiscountRate(Integer discountRate) {
        if (discountRate < 0 || discountRate > 99) {
            throw new BusinessException(ErrorCode.INVALID_DISCOUNT_RATE);
        }
    }

    private void validateOrigin(String origin) {
        if (origin.length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_ORIGIN);
        }
    }

    private void validateProductImageUrl(String productImageUrl) {
        if (productImageUrl.length() > 500) {
            throw new BusinessException(ErrorCode.INVALID_PRODUCT_IMAGE_URL);
        }
    }

    public int getEffectivePrice() {
        return salePrice != null ? salePrice : price;
    }

    public boolean isLowStock() {
        return stock < 5;
    }
}
