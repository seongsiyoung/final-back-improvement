package com.example.finalproject.payment.enums;

public enum PaymentStatus {

    READY,              // 결제 준비 완료 (결제창 진입 전)
    PENDING,            // PG 승인 요청 중 — 승인 여부 자체가 미확정
    APPROVED,           // 결제 승인 완료

    // PG 승인은 확인됐으나 로컬 반영(재고 차감·주문 생성)에 실패해
    // 취소 방향으로 복구해야 하는 상태. 취소 결과는 아직 미확정이다.
    // PENDING과 합치면 안 된다 — 스케줄러가 PG에서 DONE을 보고 주문을 다시 만든다.
    REVERSAL_PENDING,

    FAILED,             // 결제 실패 — 돈이 안 나갔거나 취소까지 완료됨

    // PG 상태를 조회로 확인했고, 자동으로 할 수 있는 안전한 다음 행동이 없음이 확정된 상태.
    // 시간이 오래 지났다는 이유로는 절대 들어오지 않는다.
    RECONCILIATION_REQUIRED,

    CANCELLED,          // 승인 전 취소 — 쓰기 경로 없음

    REFUND_REQUESTED,
    PARTIAL_REFUNDED,   // 부분 환불
    REFUNDED,            // 전액 환불
}
