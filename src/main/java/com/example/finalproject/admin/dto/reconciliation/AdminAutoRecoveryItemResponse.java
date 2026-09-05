package com.example.finalproject.admin.dto.reconciliation;

import java.time.LocalDateTime;

public record AdminAutoRecoveryItemResponse(Long id, String status, LocalDateTime updatedAt) {
}
