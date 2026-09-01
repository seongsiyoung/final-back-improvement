package com.example.finalproject.payment.dto.response;

import lombok.Getter;
import lombok.NoArgsConstructor;
import java.util.List;

@Getter
@NoArgsConstructor
public class TossConfirmResponse {

    private String paymentKey;
    private String orderId;
    private Integer totalAmount;
    private Integer balanceAmount;
    private List<CancelDetail> cancels;

    private String status;
    private String approvedAt;
    private Receipt receipt;

    private Card card;

    @Getter
    @NoArgsConstructor
    public static class Receipt {
        private String url;
    }

    @Getter
    @NoArgsConstructor
    public static class Card {
        private String company;
        private String number;
    }

    @Getter
    @NoArgsConstructor
    public static class CancelDetail {
        private Integer cancelAmount;
        private String cancelReason;
        private String canceledAt;
    }

    public int getCumulativeCanceledAmount() {
        if (totalAmount == null || balanceAmount == null) return 0;
        return totalAmount - balanceAmount;
    }
}
