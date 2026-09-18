package com.example.finalproject.order.controller;

import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.order.dto.response.GetOrderDetailResponse;
import com.example.finalproject.order.dto.response.GetOrderListResponse;
import com.example.finalproject.order.dto.response.GetStoreOrderDetailResponse;
import com.example.finalproject.order.service.OrderQueryService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderQueryService orderQueryService;

    // 주문 목록 조회 (마이페이지용)
    @GetMapping
    public ResponseEntity<ApiResponse<GetOrderListResponse>> getOrderList(
            Authentication authentication,
            @PageableDefault(
                    page = 0,
                    size = 10,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime startDate,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime endDate,

            @RequestParam(required = false)
            String searchTerm) {

        GetOrderListResponse response = orderQueryService.getOrderList(
                authentication.getName(),
                pageable,
                startDate,
                endDate,
                searchTerm
        );
        return ResponseEntity.ok(ApiResponse.success("주문 목록 조회가 완료되었습니다.", response));
    }

    // 주문 상세 조회
    @GetMapping("/{orderId}")
    public ResponseEntity<ApiResponse<GetOrderDetailResponse>> getOrderDetail(
            Authentication authentication,
            @PathVariable Long orderId) {

        GetOrderDetailResponse response = orderQueryService.getOrderDetail(authentication.getName(), orderId);
        return ResponseEntity.ok(ApiResponse.success("주문 상세 조회가 완료되었습니다.", response));
    }

    @GetMapping("/store/{storeOrderId}")
    public ResponseEntity<ApiResponse<GetStoreOrderDetailResponse>> getStoreOrderDetail(
            Authentication authentication,
            @PathVariable Long storeOrderId) {

        GetStoreOrderDetailResponse response =
                orderQueryService.getStoreOrderDetail(authentication.getName(), storeOrderId);
        return ResponseEntity.ok(ApiResponse.success("스토어 주문 상세 조회가 완료되었습니다.", response));
    }
}
