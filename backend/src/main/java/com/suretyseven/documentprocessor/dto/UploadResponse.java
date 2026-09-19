package com.suretyseven.documentprocessor.dto;

import com.suretyseven.documentprocessor.domain.DocumentStatus;

public record UploadResponse(String documentId, DocumentStatus status) {
}
