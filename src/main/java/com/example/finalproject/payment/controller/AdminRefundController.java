package com.example.finalproject.payment.controller;

import com.example.finalproject.global.response.ApiResponse;
import com.example.finalproject.payment.dto.request.PostPaymentRefundApproveRequest;
import com.example.finalproject.payment.dto.response.GetAdminRefundDetailResponse;
import com.example.finalproject.payment.dto.response.GetAdminRefundListResponse;
import com.example.finalproject.payment.enums.RefundStatus;
import com.example.finalproject.payment.service.AdminRefundCommandService;
import com.example.finalproject.payment.service.AdminRefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/refunds")
public class AdminRefundController {

    private final AdminRefundService adminRefundService;
    private final AdminRefundCommandService adminRefundCommandService;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<GetAdminRefundListResponse>>> getRefunds(
            @RequestParam(required = false) RefundStatus status,
            @PageableDefault(page = 0, size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.success(adminRefundService.getRefunds(status, pageable)));
    }

    @GetMapping("/{refundId}")
    public ResponseEntity<ApiResponse<GetAdminRefundDetailResponse>> getRefundDetail(@PathVariable Long refundId) {
        return ResponseEntity.ok(ApiResponse.success(adminRefundService.getRefundDetail(refundId)));
    }

    @PostMapping("/{refundId}/approve")
    public ResponseEntity<ApiResponse<Void>> approve(
            @PathVariable Long refundId,
            @Valid @RequestBody PostPaymentRefundApproveRequest req) {
        adminRefundService.approveAndCancel(refundId, req);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{refundId}/reject")
    public ResponseEntity<ApiResponse<Void>> reject(@PathVariable Long refundId) {
        adminRefundCommandService.reject(refundId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/{refundId}/retry")
    public ResponseEntity<ApiResponse<Void>> retry(@PathVariable Long refundId) {
        adminRefundCommandService.retry(refundId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
