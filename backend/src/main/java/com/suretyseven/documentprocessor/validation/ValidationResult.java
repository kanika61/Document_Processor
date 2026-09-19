package com.suretyseven.documentprocessor.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ValidationResult {

    private final List<String> errors;

    private ValidationResult(List<String> errors) {
        this.errors = errors;
    }

    public static ValidationResult valid() {
        return new ValidationResult(Collections.emptyList());
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(new ArrayList<>(errors));
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
