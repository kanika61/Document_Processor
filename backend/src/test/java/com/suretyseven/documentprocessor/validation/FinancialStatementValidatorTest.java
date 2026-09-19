package com.suretyseven.documentprocessor.validation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialStatementValidatorTest {

    private final FinancialStatementValidator validator = new FinancialStatementValidator();

    private Map<String, Object> validData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companyName", "Acme Corp");
        data.put("registrationNumber", "REG-123");
        data.put("address", "1 Main St");
        data.put("annualRevenue", 1000);
        data.put("documentDate", "2024-01-15");
        return data;
    }

    @Test
    void allFieldsValid_isValid() {
        assertThat(validator.validate(validData()).isValid()).isTrue();
    }

    @Test
    void missingCompanyName_isInvalid() {
        Map<String, Object> data = validData();
        data.remove("companyName");
        ValidationResult result = validator.validate(data);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).contains("companyName is required");
    }

    @Test
    void negativeAnnualRevenue_isInvalid() {
        Map<String, Object> data = validData();
        data.put("annualRevenue", -5);
        ValidationResult result = validator.validate(data);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).contains("annualRevenue must be >= 0");
    }

    @Test
    void unparseableDocumentDate_isInvalid() {
        Map<String, Object> data = validData();
        data.put("documentDate", "not-a-date");
        ValidationResult result = validator.validate(data);
        assertThat(result.isValid()).isFalse();
        assertThat(result.getErrors()).contains("documentDate must be a valid date");
    }

    @Test
    void multipleViolations_collectsAll() {
        Map<String, Object> data = new LinkedHashMap<>();
        ValidationResult result = validator.validate(data);
        assertThat(result.getErrors()).hasSize(4);
    }
}
