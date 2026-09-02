package com.example.finalproject.payment.dto.request;

import com.example.finalproject.payment.enums.ReconciliationOutcome;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class AdminResolveReconciliationRequest {

    @NotNull
    private ReconciliationOutcome outcome;

    private Integer confirmedAmount;
}
