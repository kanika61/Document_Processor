package com.suretyseven.documentprocessor.dto;

import com.suretyseven.documentprocessor.domain.DocumentStatus;

import java.time.Instant;

public record DocumentHistoryEntry(DocumentStatus status, String reason, Instant timestamp) {
}
