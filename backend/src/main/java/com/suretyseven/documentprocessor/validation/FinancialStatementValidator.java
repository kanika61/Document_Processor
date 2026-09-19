package com.suretyseven.documentprocessor.validation;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class FinancialStatementValidator implements Validator {

    @Override
    public ValidationResult validate(Map<String, Object> extractedData) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> data = extractedData == null ? Map.of() : extractedData;

        if (isBlank(data.get("companyName"))) {
            errors.add("companyName is required");
        }
        if (isBlank(data.get("registrationNumber"))) {
            errors.add("registrationNumber is required");
        }
        if (!isNonNegativeNumber(data.get("annualRevenue"))) {
            errors.add("annualRevenue must be >= 0");
        }
        if (!isValidDate(data.get("documentDate"))) {
            errors.add("documentDate must be a valid date");
        }

        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    private boolean isBlank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private boolean isNonNegativeNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue() >= 0;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s) >= 0;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private boolean isValidDate(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return false;
        }
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
