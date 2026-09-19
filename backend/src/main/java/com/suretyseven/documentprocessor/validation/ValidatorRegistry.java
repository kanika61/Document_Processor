package com.suretyseven.documentprocessor.validation;

import com.suretyseven.documentprocessor.domain.DocumentType;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ValidatorRegistry {

    private final Map<DocumentType, Validator> validators;

    public ValidatorRegistry(FinancialStatementValidator financialStatementValidator) {
        this.validators = Map.of(DocumentType.FINANCIAL_STATEMENT, financialStatementValidator);
    }

    public Validator get(DocumentType documentType) {
        Validator validator = validators.get(documentType);
        if (validator == null) {
            // Unreachable in practice: DocumentType values that pass upload-time validation
            // always have a registered Validator (see DocumentType javadoc).
            throw new IllegalStateException("No Validator registered for document type: " + documentType);
        }
        return validator;
    }
}
