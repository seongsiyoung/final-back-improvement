package com.example.finalproject.global.exception.custom;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // COMMON
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "COMMON-000", "서버 내부 오류가 발생했습니다."),
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "COMMON-001", "입력값이 유효하지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "COMMON-003", "지원하지 않는 HTTP 메서드입니다."),
    INVALID_JSON_FORMAT(HttpStatus.BAD_REQUEST, "COMMON-004", "요청 본문(JSON) 형식이 올바르지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "COMMON-005", "리소스를 찾을 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "COMMON-006", "접근 권한이 없습니다."),

    // STORE
    DUPLICATE_BUSINESS_NUMBER(HttpStatus.CONFLICT, "STORE-001", "이미 등록된 사업자등록번호입니다."),
    ALREADY_REGISTERED_STORE(HttpStatus.CONFLICT, "STORE-002", "이미 입점 신청한 사용자입니다."),
    PENDING_APPROVAL_EXISTS(HttpStatus.CONFLICT, "STORE-003", "이미 승인 대기중인 신청이 있습니다."),
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE-004", "상점을 찾을 수 없습니다."),
    INVALID_BUSINESS_HOUR(HttpStatus.BAD_REQUEST, "STORE-005", "운영 시간 정보가 올바르지 않습니다."),
    STORE_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE-006", "상점 카테고리를 찾을 수 없습니다."),
    DUPLICATE_TELECOM_SALES_NUMBER(HttpStatus.CONFLICT, "STORE-007", "이미 등록된 통신판매업 신고번호입니다."),
    STORE_PENDING_REGISTRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE-008", "취소할 수 있는 입점 신청(심사중)이 없습니다."),
    STORE_ALREADY_APPROVED(HttpStatus.BAD_REQUEST, "STORE-009", "이미 승인된 상점은 취소할 수 없습니다."),
    STORE_NOT_APPROVED(HttpStatus.FORBIDDEN, "STORE-010", "승인된 마트만 상품을 등록할 수 있습니다."),
    DUPLICATE_PRODUCT_NAME(HttpStatus.CONFLICT, "STORE-011", "이미 마트에 등록된 상품명입니다."),
    STORE_NOT_ACTIVE(HttpStatus.BAD_REQUEST, "STORE-012", "현재 영업 중인 마트가 아닙니다."),
    STORE_OUTSIDE_BUSINESS_HOURS(HttpStatus.BAD_REQUEST, "STORE-013", "영업시간이 아닙니다."),
    STORE_BUSINESS_HOUR_UPDATE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "STORE-014", "배달 불가능 상태에서만 영업시간 수정이 가능합니다."),
    STORE_DELIVERY_UNAVAILABLE(HttpStatus.BAD_REQUEST, "STORE-015", "현재 배달이 불가능한 마트가 포함되어 있습니다."),

    // AUTH
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AUTH-001", "인증이 필요합니다."),
    DUPLICATE_EMAIL(HttpStatus.BAD_REQUEST, "AUTH-002", "이미 사용 중인 이메일입니다."),
    DUPLICATE_PHONE(HttpStatus.BAD_REQUEST, "AUTH-003", "이미 가입된 휴대폰 번호입니다."),
    PHONE_VERIFICATION_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH-004", "휴대폰 인증이 필요합니다."),
    PHONE_VERIFICATION_ALREADY_USED(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH-005", "이미 사용된 인증 토큰입니다."),
    PHONE_VERIFICATION_EXPIRED(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH-006", "인증이 만료되었습니다. 다시 시도해주세요."),
    PHONE_VERIFICATION_RESEND_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "AUTH-007", "재발송 횟수를 초과했습니다. 나중에 다시 시도해주세요."),
    PHONE_VERIFICATION_NOT_FOUND(HttpStatus.BAD_REQUEST, "AUTH-008", "인증 요청을 찾을 수 없습니다. 인증번호를 먼저 요청해주세요."),
    PHONE_VERIFICATION_MISMATCH(HttpStatus.BAD_REQUEST, "AUTH-009", "인증번호가 일치하지 않습니다."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-010", "유효하지 않거나 만료된 refresh token입니다."),
    EMAIL_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH-011", "해당 이메일을 찾을 수 없습니다."),
    PASSWORD_MISMATCH(HttpStatus.UNAUTHORIZED, "AUTH-012", "비밀번호가 일치하지 않습니다."),
    USER_STATUS_FORBIDDEN(HttpStatus.FORBIDDEN, "AUTH-013", "정지되었거나 비활성화된 계정입니다."),
    REFRESH_TOKEN_MISSING(HttpStatus.BAD_REQUEST, "AUTH-014", "refresh token이 필요합니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "AUTH-015", "사용자를 찾을 수 없습니다."),
    PASSWORD_RESET_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "AUTH-016", "유효하지 않거나 만료된 비밀번호 재설정 토큰입니다."),
    PASSWORD_RESET_TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "AUTH-017", "비밀번호 재설정 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),

    // STORAGE
    FILE_UPLOAD_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE-001", "파일 업로드에 실패했습니다."),
    INVALID_FILE_EXTENSION(HttpStatus.BAD_REQUEST, "STORAGE-002", "지원하지 않는 파일 형식입니다."),

    // FAQ
    FAQ_NOT_FOUND(HttpStatus.NOT_FOUND, "FAQ-001", "FAQ를 찾을 수 없습니다."),

    // NOTICE
    NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTICE-001", "공지사항을 찾을 수 없습니다."),

    // BANNER (dev 병합)
    BANNER_NOT_FOUND(HttpStatus.NOT_FOUND, "BANNER-001", "배너를 찾을 수 없습니다."),

    // PRODUCT
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT-001", "상품을 찾을 수 없거나 해당 마트 소속이 아닙니다."),
    PRODUCT_INACTIVE(HttpStatus.BAD_REQUEST, "PRODUCT-002", "판매 중지된 상품입니다"),
    PRODUCT_OUT_OF_STOCK(HttpStatus.BAD_REQUEST, "PRODUCT-003", "품절된 상품입니다"),
    PRODUCT_STOCK_NOT_ENOUGH(HttpStatus.BAD_REQUEST, "PRODUCT-004", "재고가 부족합니다"),
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "PRODUCT-005", "카테고리를 찾을 수 없습니다."),
    PRODUCT_NOT_OWNED(HttpStatus.FORBIDDEN, "PRODUCT-006", "해당 상품에 대한 권한이 없습니다."),
    INVALID_PRODUCT_NAME(HttpStatus.BAD_REQUEST, "PRODUCT-007", "상품명은 빈 값이거나 200자를 초과할 수 없습니다."),
    INVALID_PRICE(HttpStatus.BAD_REQUEST, "PRODUCT-008", "가격은 1원 이상이어야 합니다."),
    INVALID_DISCOUNT_RATE(HttpStatus.BAD_REQUEST, "PRODUCT-009", "할인율은 0에서 99 사이여야 합니다."),
    INVALID_STOCK(HttpStatus.BAD_REQUEST, "PRODUCT-010", "재고는 0개 이상이어야 합니다."),
    INVALID_ORIGIN(HttpStatus.BAD_REQUEST, "PRODUCT-011", "원산지는 100자를 초과할 수 없습니다."),
    INVALID_PRODUCT_IMAGE_URL(HttpStatus.BAD_REQUEST, "PRODUCT-012", "상품 이미지 URL은 500자를 초과할 수 없습니다."),
    PRODUCT_CHANGE_NOT_ALLOWED_DURING_BUSINESS_HOURS(HttpStatus.BAD_REQUEST, "PRODUCT-013",
            "운영 시간에는 상품 정보를 수정, 삭제 할 수 없습니다."),
    INSUFFICIENT_STOCK(HttpStatus.BAD_REQUEST, "PRODUCT-014", "재고가 부족합니다."),
    INVALID_STOCK_QUANTITY(HttpStatus.BAD_REQUEST, "PRODUCT-015", "수량은 1 이상이어야 합니다."),
    PRODUCT_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "PRODUCT-016", "현재 상품을 주문할 수 없습니다."),

    // SUBSCRIPTION PRODUCT
    SUBSCRIPTION_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "SUBSCRIPTION-001", "구독 상품을 찾을 수 없거나 해당 마트 소속이 아닙니다."),
    SUBSCRIPTION_PRODUCT_INVALID_STATUS(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-002", "해당 구독 상품 상태로 전환할 수 없습니다."),
    SUBSCRIPTION_PRODUCT_DELETION_REQUIRES_INACTIVE(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-003",
            "구독 상품을 숨김 상태로 전환한 뒤 삭제를 요청할 수 있습니다."),
    SUBSCRIPTION_PRODUCT_HAS_SUBSCRIBERS(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-004",
            "구독자가 있어 즉시 삭제할 수 없습니다. 구독자가 0명일 때만 삭제 가능합니다."),
    SUBSCRIPTION_PRODUCT_NOTIFY_REQUIRES_PENDING_DELETE(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-010",
            "삭제 예정 상태인 구독 상품에만 구독자 알림을 발송할 수 있습니다."),
    SUBSCRIPTION_PRODUCT_HAS_NO_ITEMS(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-009", "구독 상품에 구성 품목이 없습니다."),

    // SUBSCRIPTION
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "SUBSCRIPTION-005", "구독을 찾을 수 없습니다."),
    SUBSCRIPTION_FORBIDDEN(HttpStatus.FORBIDDEN, "SUBSCRIPTION-006", "본인의 구독에만 접근할 수 있습니다."),
    SUBSCRIPTION_INVALID_STATUS(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-007", "해당 상태에서는 요청한 작업을 수행할 수 없습니다."),
    SUBSCRIPTION_INVALID_DELIVERY_TIME_SLOT(HttpStatus.BAD_REQUEST, "SUBSCRIPTION-008",
            "배송 시간대는 08:00~11:00, 11:00~14:00, 14:00~17:00, 17:00~20:00 중 하나여야 합니다."),

    // CART
    CART_NOT_FOUND(HttpStatus.NOT_FOUND, "CART-001", "장바구니가 없습니다"),
    CART_PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "CART-002", "장바구니에 해당 상품이 없습니다"),

    // ADDRESS
    ADDRESS_NOT_FOUND(HttpStatus.NOT_FOUND, "ADDRESS-001", "주소를 조회할 수 없습니다."),
    ADDRESS_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "ADDRESS-002", "배송지는 최대 5개까지 등록할 수 있습니다."),
    DUPLICATE_ADDRESS_NAME(HttpStatus.CONFLICT, "ADDRESS-003", "이미 존재하는 배송지 이름입니다."),
    DUPLICATE_ADDRESS(HttpStatus.CONFLICT, "ADDRESS-004", "이미 등록된 배송지 주소입니다."),
    ADDRESS_DELETE_MIN_REQUIRED(HttpStatus.BAD_REQUEST, "ADDRESS-005", "배송지는 최소 1개 이상 등록되어 있어야 합니다."),

    // DELIVERY
    DISTANCE_CALCULATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY-001", "배달비 계산에 실패했습니다."),
    DELIVERY_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "DELIVERY-002", "배달 가능한 지역이 아닙니다."),
    DELIVERY_NOT_FOUND(HttpStatus.NOT_FOUND, "DELIVERY-003", "배달 정보를 찾을 수 없습니다."),
    DELIVERY_ALREADY_LOCKED(HttpStatus.CONFLICT, "DELIVERY-004", "이미 다른 라이더가 수락 중인 배달입니다."),
    DELIVERY_ALREADY_MATCHED(HttpStatus.CONFLICT, "DELIVERY-005", "이미 매칭이 완료된 배달입니다."),
    RIDER_NOT_FOUND(HttpStatus.NOT_FOUND, "DELIVERY-006", "라이더 정보를 찾을 수 없습니다."),
    DELIVERY_INVALID_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "DELIVERY-007", "유효하지 않은 배달 상태 변경입니다."),
    DELIVERY_RIDER_NOT_ASSIGNED(HttpStatus.BAD_REQUEST, "DELIVERY-008", "배달에 배정된 라이더가 아닙니다."),

    // RIDER
    RIDER_STATUS_LOCKED_DELIVERING(HttpStatus.CONFLICT, "RIDER-001", "배달 중에는 상태를 변경할 수 없습니다."),
    RIDER_ALREADY_REGISTERED(HttpStatus.CONFLICT, "RIDER-002", "이미 라이더로 등록되어 있습니다."),
    RIDER_APPROVAL_ALREADY_EXISTS(HttpStatus.CONFLICT, "RIDER-003", "이미 대기 중인 신청이 있습니다."),
    RIDER_LOCATION_NOT_FOUND(HttpStatus.NOT_FOUND, "RIDER-004", "라이더 위치 정보를 찾을 수 없습니다."),
    RIDER_MAX_DELIVERY_EXCEEDED(HttpStatus.CONFLICT, "RIDER-005", "동시에 진행할 수 있는 최대 배달 수를 초과했습니다."),

    // APPROVAL
    APPROVAL_NOT_FOUND(HttpStatus.NOT_FOUND, "APPROVAL-001", "신청 정보를 찾을 수 없습니다."),
    APPROVAL_NOT_PENDING(HttpStatus.BAD_REQUEST, "APPROVAL-002", "대기 중인 신청만 삭제할 수 있습니다."),
    APPROVAL_NOT_OWNED(HttpStatus.FORBIDDEN, "APPROVAL-003", "본인의 신청만 삭제할 수 있습니다."),

    // INQUIRY
    INQUIRY_NOT_FOUND(HttpStatus.NOT_FOUND, "INQUIRY-001", "문의를 조회할 수 없습니다."),
    INQUIRY_ALREADY_ANSWERED(HttpStatus.BAD_REQUEST, "INQUIRY-002", "이미 답변된 문의입니다."),

    // ADMIN
    ADMIN_AUTHORITY_REQUIRED(HttpStatus.FORBIDDEN, "ADMIN-001", "관리자만 접근 가능합니다."),

    // NOTIFICATION
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "NOTIFICATION-001", "알림을 조회할 수 없습니다."),
    NOTIFICATION_OWNER_MISMATCH(HttpStatus.FORBIDDEN, "NOTIFICATION-002", "자신의 알림만 접근할 수 있습니다."),
    UNSUPPORTED_EMAIL_TYPE(HttpStatus.INTERNAL_SERVER_ERROR, "NOTIFICATION-003", "이메일 전송에 있어서 서버 에러가 터졌습니다."),


    // PAYMENT
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-001", "결제를 조회할 수 없습니다."),
    ALREADY_PROCESSED_PAYMENT(HttpStatus.BAD_REQUEST, "PAYMENT-002", "이미 처리된 결제입니다."),
    PAYMENT_METHOD_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT-003", "결제 수단을 찾을 수 없습니다."),
    PAYMENT_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-004", "결제에 실패했습니다."),
    PAYMENT_CANCEL_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT-005", "결제 취소에 실패했습니다."),
    INVALID_CANCEL_AMOUNT(HttpStatus.BAD_REQUEST, "PAYMENT-006", "취소 금액이 올바르지 않습니다."),
    INVALID_REFUND_AMOUNT(HttpStatus.BAD_REQUEST, "PAYMENT-007", "환불 금액이 결제 금액을 초과합니다."),
    INVALID_PAYMENT_CANCEL_STATUS(HttpStatus.BAD_REQUEST, "PAYMENT-008", "결제를 취소할 수 있는 상태가 아닙니다."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "PAYMENT-009", "결제 결과를 확인 중입니다."),


    // ORDER (order-checkout)
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-001", "주문을 찾을 수 없습니다."),
    POINTS_MUST_BE_NON_NEGATIVE(HttpStatus.BAD_REQUEST, "ORDER-002", "사용 포인트는 0 이상이어야 합니다."),
    POINTS_EXCEED_ORDER_TOTAL(HttpStatus.BAD_REQUEST, "ORDER-003", "사용 포인트는 상품금액+배송비를 초과할 수 없습니다."),
    DISCOUNT_EXCEEDS_PRODUCT_TOTAL(HttpStatus.BAD_REQUEST, "ORDER-005", "쿠폰 할인 금액이 상품 금액을 초과할 수 없습니다."),
    COUPON_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER-006", "쿠폰을 찾을 수 없거나 사용할 수 없습니다."),
    INVALID_ORDER_AMOUNT(HttpStatus.BAD_REQUEST, "ORDER-007", "최종금액은 0 이상입니다."),
    INVALID_ORDER_TYPE(HttpStatus.INTERNAL_SERVER_ERROR, "ORDER-008", "존재하지 않는 주문 방식입니다."),
    ORDER_NOT_PAID(HttpStatus.BAD_REQUEST, "ORDER-00", "결제 완료된 주문만 주문접수가 가능합니다."),
    ORDER_CANNOT_BE_CANCELLED(HttpStatus.BAD_REQUEST, "ORDER-009", "접수 완료된 주문이 포함되어 있어 취소할 수 없습니다."),

    // STORE_ORDER
    STORE_ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE-ORDER-001", "주문을 찾을 수 없습니다."),
    STORE_ORDER_NOT_BELONG_TO_STORE(HttpStatus.FORBIDDEN, "STORE-ORDER-002", "해당 상점의 주문이 아닙니다."),
    STORE_ORDER_NOT_PENDING(HttpStatus.BAD_REQUEST, "STORE-ORDER-003", "접수 대기 중인 주문이 아닙니다."),
    STORE_ORDER_NOT_ACCEPTED(HttpStatus.BAD_REQUEST, "STORE-ORDER-004", "접수된 주문이 아닙니다."),
    STORE_ORDER_NOT_READY(HttpStatus.BAD_REQUEST, "STORE-ORDER-005", "준비 완료된 주문이 아닙니다."),
    STORE_ORDER_NOT_PICKED_UP(HttpStatus.BAD_REQUEST, "STORE-ORDER-006", "픽업 완료된 주문이 아닙니다."),
    STORE_ORDER_NOT_DELIVERING(HttpStatus.BAD_REQUEST, "STORE-ORDER-007", "배송 중인 주문이 아닙니다."),
    STORE_ORDER_ALREADY_PROCESSED(HttpStatus.CONFLICT, "STORE-ORDER-008", "이미 처리된 주문입니다."),
    INVALID_STORE_ORDER_REFUND_STATUS(HttpStatus.CONFLICT, "STORE-ORDER-009", "환불 과정에서 문제가 발생했습니다."),

    // REVIEW
    REVIEW_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "REVIEW-001", "리뷰 작성이 불가능한 주문 상태입니다."),
    REVIEW_ALREADY_EXISTS(HttpStatus.CONFLICT, "REVIEW-002", "이미 리뷰가 존재합니다."),
    REVIEW_NOT_FOUND(HttpStatus.BAD_REQUEST, "REVIEW-003", "리뷰를 찾을 수 없습니다."),
    REVIEW_MODIFICATION_PERIOD_EXPIRED(HttpStatus.BAD_REQUEST, "REVIEW-004", "리뷰 수정/삭제 가능 기간이 지났습니다."),
    REVIEW_REPLY_ALREADY_EXISTS(HttpStatus.CONFLICT, "REVIEW-005", "이미 답글이 존재합니다."),

    // REFUND
    REFUND_ALREADY_REQUESTED(HttpStatus.CONFLICT, "REFUND-001", "이미 환불 요청이 접수된 주문입니다."),
    REFUND_REQUEST_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "REFUND-002", "현재 상태에서는 환불 요청이 불가능합니다."),
    REFUND_EXPIRED(HttpStatus.BAD_REQUEST, "REFUND-003", "환불 가능 기간(배송 완료 후 48시간)이 지났습니다."),
    REFUND_NOT_FOUND(HttpStatus.NOT_FOUND, "REFUND-004", "환불 요청 정보를 찾을 수 없습니다."),
    INVALID_REFUND_STATUS(HttpStatus.BAD_REQUEST, "REFUND-005", "환불 상태가 올바르지 않습니다."),
    INVALID_REFUND_ROLLBACK_STATE(HttpStatus.INTERNAL_SERVER_ERROR, "REFUND-006", "환불 요청 원복 상태가 유실되었습니다."),
    INVALID_STORE_ORDER_STATUS(HttpStatus.INTERNAL_SERVER_ERROR, "STORE_ORDER-007", "주문 상태 정보가 올바르지 않습니다."),

    // 회원가입 검증 (422)
    TERMS_PRIVACY_NOT_AGREED(HttpStatus.UNPROCESSABLE_ENTITY, "AUTH-018", "필수 약관에 동의해야 합니다."),

    ;

    private final HttpStatus status;
    private final String code;
    private final String message;

    /**
     * API 에러 응답 코드: 409 → ERR_CONFLICT, 400 → ERR_VALIDATION, 422 → ERR_UNPROCESSABLE
     */
    public String getApiCode() {
        if (status == HttpStatus.CONFLICT) {
            return "ERR_CONFLICT";
        }
        if (status == HttpStatus.BAD_REQUEST) {
            return "ERR_VALIDATION";
        }
        if (status == HttpStatus.UNPROCESSABLE_ENTITY) {
            return "ERR_UNPROCESSABLE";
        }
        return code;
    }
}
