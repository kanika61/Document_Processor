package com.suretyseven.documentprocessor.dto;

import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.domain.DocumentType;

import java.time.Instant;

/**
 * Projected list-view fields only — deliberately excludes extractedResult, which can be a
 * sizeable JSON blob and has no reason to be pulled back for every row of a paged list.
 */
public record DocumentSummary(
        String documentId,
        String filename,
        DocumentType documentType,
        DocumentStatus status,
        Long fileSizeBytes,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
