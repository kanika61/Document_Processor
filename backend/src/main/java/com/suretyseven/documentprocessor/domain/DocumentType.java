package com.suretyseven.documentprocessor.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Fixed enum of supported document types. Any value that doesn't match a constant here
 * is rejected at upload time with 400 Bad Request before a row is ever created — so a
 * {@link com.suretyseven.documentprocessor.validation.Validator} is always available for
 * any DocumentType that reaches the processing stage.
 */
public enum DocumentType {
    FINANCIAL_STATEMENT;

    public static Optional<DocumentType> fromString(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.name().equals(value))
                .findFirst();
    }
}
