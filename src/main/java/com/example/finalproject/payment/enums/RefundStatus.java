package com.example.finalproject.payment.enums;

public enum RefundStatus {

    REQUESTED,      // 고객·관리자의 환불 요청이 생성됨. 관리자가 아직 안 봄

    // PG 처리 대상으로 확정됐으나 PG 결과가 아직 미확정.
    // PG 호출 직전에 찍는다 — 호출 전에 죽어도 스케줄러가 다시 잡을 수 있어야 한다.
    PG_PENDING,

    // PG는 취소했고 로컬 장부 반영만 남은 상태.
    // applyRefund()가 실패해도 이 사실이 사라지지 않도록 독립 트랜잭션으로 찍는다.
    PG_APPROVED,

    APPROVED,       // PG와 로컬 반영 모두 완료. 정산 차감 대상
    PG_REJECTED,    // PG가 취소 요청을 명확히 거절
    REJECTED,       // 관리자가 환불 요청 자체를 거절

    // PG 환불은 확인됐으나 로컬 장부를 자동으로 맞출 수 없음이 확정된 상태.
    RECONCILIATION_REQUIRED
}
