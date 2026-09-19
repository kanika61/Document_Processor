package com.suretyseven.documentprocessor.dto;

import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.domain.DocumentType;

import java.time.Instant;
import java.util.Map;

public record DocumentDetailResponse(
        String documentId,
        DocumentStatus status,
        DocumentType documentType,
        String filename,
        Long fileSizeBytes,
        Instant createdAt,
        Instant updatedAt,
        Map<String, Object> extractedResult,
        String failureReason
) {
}
