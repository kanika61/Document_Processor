package com.suretyseven.documentprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.documentprocessor.config.DocumentProcessingProperties;
import com.suretyseven.documentprocessor.domain.Document;
import com.suretyseven.documentprocessor.domain.DocumentHistory;
import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.domain.DocumentType;
import com.suretyseven.documentprocessor.processor.DocumentProcessor;
import com.suretyseven.documentprocessor.processor.DocumentProcessorRegistry;
import com.suretyseven.documentprocessor.processor.FileType;
import com.suretyseven.documentprocessor.processor.ProcessResult;
import com.suretyseven.documentprocessor.repository.DocumentHistoryRepository;
import com.suretyseven.documentprocessor.repository.DocumentRepository;
import com.suretyseven.documentprocessor.storage.FileStorage;
import com.suretyseven.documentprocessor.validation.ValidationResult;
import com.suretyseven.documentprocessor.validation.Validator;
import com.suretyseven.documentprocessor.validation.ValidatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the async retry loop (assignment section 13, cases 2-5). Covers the loop logic
 * directly (not via Spring's @Async proxy) with mocked collaborators so outcomes are fully
 * deterministic — real randomness lives only in PdfProcessor.
 */
class DocumentProcessingServiceTest {

    private DocumentRepository documentRepository;
    private DocumentHistoryRepository documentHistoryRepository;
    private DocumentProcessorRegistry processorRegistry;
    private ValidatorRegistry validatorRegistry;
    private FileStorage fileStorage;
    private DocumentProcessor processor;
    private Validator validator;
    private Document document;
    private DocumentProcessingService service;

    private static final String DOC_ID = "DOC-test-1";

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentHistoryRepository = mock(DocumentHistoryRepository.class);
        processorRegistry = mock(DocumentProcessorRegistry.class);
        validatorRegistry = mock(ValidatorRegistry.class);
        fileStorage = mock(FileStorage.class);
        processor = mock(DocumentProcessor.class);
        validator = mock(Validator.class);

        document = new Document();
        document.setDocumentId(DOC_ID);
        document.setFilename("statement.pdf");
        document.setDocumentType(DocumentType.FINANCIAL_STATEMENT);
        document.setContentHash("hash");
        document.setFilePath("./uploaded-documents/hash.pdf");
        document.setStatus(DocumentStatus.UPLOADED);
        document.setCreatedAt(Instant.now());
        document.setUpdatedAt(Instant.now());

        when(documentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(processorRegistry.get(FileType.PDF)).thenReturn(processor);
        when(validatorRegistry.get(DocumentType.FINANCIAL_STATEMENT)).thenReturn(validator);
        when(fileStorage.read(document.getFilePath())).thenReturn(new byte[]{1, 2, 3});

        DocumentProcessingProperties properties = new DocumentProcessingProperties();
        properties.setMaxRetryAttempts(3);
        properties.setRetryBackoffMs(0);

        service = new DocumentProcessingService(documentRepository, documentHistoryRepository,
                processorRegistry, validatorRegistry, fileStorage, properties, new ObjectMapper());
    }

    @Test
    void successfulProcessing_endsProcessedWithExtractedResult() {
        Map<String, Object> extracted = Map.of("companyName", "Acme");
        when(processor.process(any(), eq(DOC_ID))).thenReturn(ProcessResult.success(extracted));
        when(validator.validate(extracted)).thenReturn(ValidationResult.valid());

        service.processDocumentAsync(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        assertThat(document.getFailureReason()).isNull();
        assertThat(document.getExtractedResult()).contains("Acme");
        verify(processor, times(1)).process(any(), eq(DOC_ID));
        verify(documentHistoryRepository, times(2)).save(any(DocumentHistory.class)); // PROCESSING, PROCESSED
    }

    @Test
    void successWithInvalidExtractedData_endsFailedValidationNotRetried() {
        Map<String, Object> extracted = Map.of();
        when(processor.process(any(), eq(DOC_ID))).thenReturn(ProcessResult.success(extracted));
        when(validator.validate(extracted)).thenReturn(ValidationResult.invalid(List.of("companyName is required")));

        service.processDocumentAsync(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getFailureReason()).startsWith("VALIDATION_FAILED");
        verify(processor, times(1)).process(any(), eq(DOC_ID)); // no retry — deterministic failure
        verify(documentHistoryRepository, times(2)).save(any(DocumentHistory.class)); // PROCESSING, FAILED
    }

    @Test
    void timeoutExhaustsRetries_endsFailedAfterExactlyThreeAttempts() {
        when(processor.process(any(), eq(DOC_ID)))
                .thenReturn(ProcessResult.of(com.suretyseven.documentprocessor.processor.ProcessOutcome.TIMEOUT));

        service.processDocumentAsync(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getFailureReason()).isEqualTo("TIMEOUT");
        verify(processor, times(3)).process(any(), eq(DOC_ID));
        // PROCESSING, FAILED, PROCESSING, FAILED, PROCESSING, FAILED
        verify(documentHistoryRepository, times(6)).save(any(DocumentHistory.class));
    }

    @Test
    void failureFollowedBySuccessfulRetry_endsProcessed() {
        Map<String, Object> extracted = Map.of("companyName", "Acme");
        when(processor.process(any(), eq(DOC_ID)))
                .thenReturn(ProcessResult.of(com.suretyseven.documentprocessor.processor.ProcessOutcome.TIMEOUT))
                .thenReturn(ProcessResult.of(com.suretyseven.documentprocessor.processor.ProcessOutcome.TIMEOUT))
                .thenReturn(ProcessResult.success(extracted));
        when(validator.validate(extracted)).thenReturn(ValidationResult.valid());

        service.processDocumentAsync(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSED);
        assertThat(document.getFailureReason()).isNull();
        verify(processor, times(3)).process(any(), eq(DOC_ID));
        // PROCESSING, FAILED, PROCESSING, FAILED, PROCESSING, PROCESSED
        verify(documentHistoryRepository, times(6)).save(any(DocumentHistory.class));
    }

    @Test
    void invalidResultOutcome_endsFailedWithoutRetry() {
        when(processor.process(any(), eq(DOC_ID)))
                .thenReturn(ProcessResult.of(com.suretyseven.documentprocessor.processor.ProcessOutcome.INVALID_RESULT));

        service.processDocumentAsync(DOC_ID);

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(document.getFailureReason()).isEqualTo("INVALID_RESULT");
        verify(processor, times(1)).process(any(), eq(DOC_ID));
        verify(documentHistoryRepository, times(2)).save(any(DocumentHistory.class)); // PROCESSING, FAILED
    }
}
