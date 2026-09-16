package com.example.finalproject.order.service;

import static java.util.stream.Collectors.groupingBy;

import com.example.finalproject.delivery.service.DeliveryFeeService;
import com.example.finalproject.global.exception.custom.BusinessException;
import com.example.finalproject.global.exception.custom.ErrorCode;
import com.example.finalproject.order.domain.Cart;
import com.example.finalproject.order.domain.CartProduct;
import com.example.finalproject.order.dto.request.PatchCartUpdateRequest;
import com.example.finalproject.order.dto.request.PostCartAddRequest;
import com.example.finalproject.order.dto.response.GetCartResponse;
import com.example.finalproject.order.dto.response.GetCartStoreGroupResponse;
import com.example.finalproject.order.repository.CartProductRepository;
import com.example.finalproject.order.repository.CartRepository;
import com.example.finalproject.product.domain.Product;
import com.example.finalproject.product.repository.ProductRepository;
import com.example.finalproject.user.domain.User;
import com.example.finalproject.user.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private static final int LOW_STOCK_THRESHOLD = 5;

    private final CartRepository cartRepository;
    private final CartProductRepository cartProductRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final DeliveryFeeService deliveryFeeService;

    @Transactional
    public GetCartResponse addToCart(String username, PostCartAddRequest request) {
        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));

        validatePurchasable(product);

        Cart cart = getOrCreateCart(user);

        addOrUpdateCartProduct(cart, product, request.getQuantity());

        log.info("[장바구니] 장바구니에 상품을 추가합니다. 사용자={}, 상품ID={}, 수량={}", username, product.getId(), request.getQuantity());
        return getMyCart(username);
    }

    @Transactional(readOnly = true)
    public GetCartResponse getMyCart(String username) {

        User user = userRepository.findByEmail(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

        Cart cart = cartRepository.findByUser_Email(username).orElse(null);

        if (cart == null) {
            log.info("[장바구니] 장바구니 상품 조회 완료. 사용자={}, 장바구니 비어 있음", username);
            return GetCartResponse.empty();
        }

        /**
         * n + 1 문제
         */
        List<CartProduct> cartProducts =
                cartProductRepository.findAllByCartId(cart.getId());

        List<GetCartStoreGroupResponse> stores = cartProducts.stream()
                .collect(groupingBy(cp -> cp.getStore().getId()))
                .values().stream()
                .map(group -> {

                    Long storeId = group.get(0).getStore().getId();

                    int deliveryFee =
                            deliveryFeeService.calculateDeliveryFee(user.getId(), storeId);

                    return GetCartStoreGroupResponse.from(group, deliveryFee);
                })
                .toList();

        int totalPrice = stores.stream()
                .mapToInt(GetCartStoreGroupResponse::getStoreProductPrice)
                .sum();

        int itemCount = cartProducts.size();
        log.info("[장바구니] 장바구니 상품 조회 완료. 사용자={}, 상품 수={}건, 마트 수={}곳", username, itemCount, stores.size());
        return GetCartResponse.of(
                cart.getId(),
                stores,
                totalPrice
        );
    }

    @Transactional
    public GetCartResponse updateQuantity(String username, Long productId, PatchCartUpdateRequest request) {
        Cart cart = cartRepository.findByUser_Email(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));

        CartProduct cp = cartProductRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_PRODUCT_NOT_FOUND));

        Product product = cp.getProduct();
        int requestQuantity = request.getQuantity();

        validatePurchasable(product);

        validateStock(product, requestQuantity);
        cp.changeQuantity(requestQuantity);

        log.info("[장바구니] 장바구니 상품 수량을 변경합니다. 사용자={}, 상품ID={}, 수량={}", username, productId, requestQuantity);
        return getMyCart(username);
    }

    
    @Transactional
    public GetCartResponse removeItem(String username, Long productId) {
        Cart cart = cartRepository.findByUser_Email(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_NOT_FOUND));

        CartProduct cp = cartProductRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CART_PRODUCT_NOT_FOUND));

        cartProductRepository.delete(cp);
        log.info("[장바구니] 장바구니에서 상품을 제거합니다. 사용자={}, 상품ID={}", username, productId);
        return getMyCart(username);
    }


    @Transactional
    public GetCartResponse clearCart(String username) {
        Cart cart = cartRepository.findByUser_Email(username)
                .orElse(null);

        if (cart == null) {
            return GetCartResponse.empty();
        }

        cartProductRepository.deleteAllByCartId(cart.getId());
        log.info("[장바구니] 장바구니를 비웁니다. 사용자={}", username);
        return getMyCart(username);
    }

    private Cart getOrCreateCart(User user) {
        return cartRepository.findByUserId(user.getId())
                .orElseGet(() -> createCart(user));
    }

    private Cart createCart(User user) {
        return cartRepository.save(Cart.create(user));
    }

    private void addOrUpdateCartProduct(Cart cart, Product product, int requestedQty) {
        CartProduct cartProduct = cartProductRepository
                .findByCartIdAndProductId(cart.getId(), product.getId())
                .orElse(null);

        if (cartProduct == null) {
            addNewCartProduct(cart, product, requestedQty);
        } else {
            increaseCartProductQuantity(cartProduct, product, requestedQty);
        }
    }

    private void addNewCartProduct(Cart cart, Product product, int qty) {
        validateStock(product, qty);

        cartProductRepository.save(
                CartProduct.builder()
                        .cart(cart)
                        .product(product)
                        .store(product.getStore())
                        .quantity(qty)
                        .build()
        );
    }

    private void increaseCartProductQuantity(
            CartProduct cartProduct,
            Product product,
            int requestedQty) {
        int newQty = cartProduct.getQuantity() + requestedQty;
        validateStock(product, newQty);

        cartProduct.changeQuantity(newQty);
    }

    private void validateStock(Product product, int requestedQty) {
        if (requestedQty > product.getStock()) {
            throw new BusinessException(ErrorCode.PRODUCT_STOCK_NOT_ENOUGH);
        }
    }

    private void validatePurchasable(Product product) {
        if (!Boolean.TRUE.equals(product.getIsActive())) {
            throw new BusinessException(ErrorCode.PRODUCT_INACTIVE);
        }
        if (product.getStock() == null || product.getStock() <= 0) {
            throw new BusinessException(ErrorCode.PRODUCT_OUT_OF_STOCK);
        }
    }
}
