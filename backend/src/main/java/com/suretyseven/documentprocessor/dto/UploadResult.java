package com.suretyseven.documentprocessor.dto;

import com.suretyseven.documentprocessor.domain.DocumentStatus;

/** Outcome of {@link com.suretyseven.documentprocessor.service.DocumentService#upload}. */
public record UploadResult(String documentId, DocumentStatus status) {
}
