package com.example.finalproject.admin.dto.reconciliation;

import java.time.LocalDateTime;
import java.util.Map;

public record AdminReconciliationSummaryResponse(long totalCount, LocalDateTime oldestUpdatedAt,
                                                 Map<String, Long> byStatus) {
}
