package com.suretyseven.documentprocessor.validation;

import java.util.Map;

/** One implementation per {@link com.suretyseven.documentprocessor.domain.DocumentType}. */
public interface Validator {

    ValidationResult validate(Map<String, Object> extractedData);
}
