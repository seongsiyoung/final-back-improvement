package com.example.finalproject.payment.service;

/**
 * 짧은 트랜잭션이 밖으로 넘기는 환불 대상.
 *
 * <p>PG 호출은 트랜잭션 밖에서 하므로 오케스트레이터는 엔티티가 아닌 식별자와 스칼라 값만 다룬다.
 */
public record RefundTarget(Long orderId, Long storeOrderId, int amount, String reason) {
}
