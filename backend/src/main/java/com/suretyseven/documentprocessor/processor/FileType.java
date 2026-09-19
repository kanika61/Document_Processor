package com.suretyseven.documentprocessor.processor;

import java.util.Locale;
import java.util.Optional;

/**
 * File-format axis, kept deliberately separate from {@link com.suretyseven.documentprocessor.domain.DocumentType}.
 * A {@link DocumentProcessor} is resolved by FileType (how the bytes are structured), while a
 * {@link com.suretyseven.documentprocessor.validation.Validator} is resolved by DocumentType
 * (what business rules the extracted fields must satisfy). Adding a new format later (e.g. EXCEL)
 * only touches this enum + one new DocumentProcessor + one registry entry.
 */
public enum FileType {
    PDF(".pdf");

    private final String extension;

    FileType(String extension) {
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static Optional<FileType> fromFilename(String filename) {
        if (filename == null) {
            return Optional.empty();
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        for (FileType type : values()) {
            if (lower.endsWith(type.extension)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
