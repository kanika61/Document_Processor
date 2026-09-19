package com.suretyseven.documentprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.documentprocessor.config.DocumentProcessingProperties;
import com.suretyseven.documentprocessor.domain.Document;
import com.suretyseven.documentprocessor.domain.DocumentHistory;
import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.processor.DocumentProcessor;
import com.suretyseven.documentprocessor.processor.DocumentProcessorRegistry;
import com.suretyseven.documentprocessor.processor.FileType;
import com.suretyseven.documentprocessor.processor.ProcessOutcome;
import com.suretyseven.documentprocessor.processor.ProcessResult;
import com.suretyseven.documentprocessor.repository.DocumentHistoryRepository;
import com.suretyseven.documentprocessor.repository.DocumentRepository;
import com.suretyseven.documentprocessor.storage.FileStorage;
import com.suretyseven.documentprocessor.validation.ValidationResult;
import com.suretyseven.documentprocessor.validation.Validator;
import com.suretyseven.documentprocessor.validation.ValidatorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Owns the ASYNCHRONOUS flow only: the retry loop, calling the DocumentProcessor, dispatching
 * to the right Validator, and all document_history writes that happen during processing.
 *
 * Must live on a separate Spring bean from whatever calls processDocumentAsync (the controller
 * calls it directly) — calling it via `this.` from the same class that also triggers it would
 * silently bypass the @Async proxy.
 *
 * Deliberately has NO class-level @Transactional: each repository save below commits in its
 * own transaction via Spring Data JPA's per-call transactionality, so a concurrent
 * GET /documents/{id}/history sees each state transition as it happens rather than only after
 * the whole retry loop finishes.
 */
@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentRepository documentRepository;
    private final DocumentHistoryRepository documentHistoryRepository;
    private final DocumentProcessorRegistry processorRegistry;
    private final ValidatorRegistry validatorRegistry;
    private final FileStorage fileStorage;
    private final DocumentProcessingProperties properties;
    private final ObjectMapper objectMapper;

    public DocumentProcessingService(DocumentRepository documentRepository,
                                      DocumentHistoryRepository documentHistoryRepository,
                                      DocumentProcessorRegistry processorRegistry,
                                      ValidatorRegistry validatorRegistry,
                                      FileStorage fileStorage,
                                      DocumentProcessingProperties properties,
                                      ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.documentHistoryRepository = documentHistoryRepository;
        this.processorRegistry = processorRegistry;
        this.validatorRegistry = validatorRegistry;
        this.fileStorage = fileStorage;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Async("documentProcessingExecutor")
    public void processDocumentAsync(String documentId) {
        try {
            transition(documentId, DocumentStatus.PROCESSING, null, null);

            Document document = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));

            FileType fileType = FileType.fromFilename(document.getFilename())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unsupported file type for documentId=" + documentId));
            DocumentProcessor processor = processorRegistry.get(fileType);
            byte[] fileBytes = fileStorage.read(document.getFilePath());

            int maxAttempts = properties.getMaxRetryAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                ProcessResult result = processor.process(fileBytes, documentId);
                log.info("documentId={} attempt={}/{} outcome={}", documentId, attempt, maxAttempts, result.outcome());

                if (result.outcome() == ProcessOutcome.SUCCESS) {
                    handleSuccess(documentId, document, result, attempt);
                    return;
                }

                if (result.outcome() == ProcessOutcome.INVALID_RESULT) {
                    // Deterministic outcome for this file — do not retry.
                    transition(documentId, DocumentStatus.FAILED, ProcessOutcome.INVALID_RESULT.name(), attempt);
                    return;
                }

                // TIMEOUT or ERROR — retryable flakiness.
                transition(documentId, DocumentStatus.FAILED, result.outcome().name(), attempt);
                if (attempt == maxAttempts) {
                    return; // terminal — status stays FAILED
                }
                transition(documentId, DocumentStatus.PROCESSING, null, attempt);
                sleepBackoff();
            }
        } catch (Exception e) {
            // Exceptions thrown inside a void @Async method are silently swallowed by Spring —
            // never let one escape uncaught, or the document is stuck in PROCESSING forever.
            log.error("documentId={} unexpected error during processing", documentId, e);
            safelyMarkErrored(documentId);
        }
    }

    private void handleSuccess(String documentId, Document document, ProcessResult result, int attempt) {
        Validator validator = validatorRegistry.get(document.getDocumentType());
        ValidationResult validation = validator.validate(result.extractedData());
        if (validation.isValid()) {
            markProcessed(documentId, result.extractedData(), attempt);
        } else {
            String reason = "VALIDATION_FAILED: " + String.join(", ", validation.getErrors());
            transition(documentId, DocumentStatus.FAILED, reason, attempt);
        }
    }

    private void transition(String documentId, DocumentStatus status, String reason, Integer retryCount) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        DocumentStatus previous = document.getStatus();
        Instant now = Instant.now();

        document.setStatus(status);
        document.setFailureReason(status == DocumentStatus.FAILED ? reason : null);
        if (retryCount != null) {
            document.setRetryCount(retryCount);
        }
        document.setUpdatedAt(now);
        documentRepository.save(document);
        documentHistoryRepository.save(new DocumentHistory(documentId, status, reason, now));

        log.info("documentId={} attempt={} transition={}->{} reason={}",
                documentId, retryCount, previous, status, reason);
    }

    private void markProcessed(String documentId, Map<String, Object> extractedData, int attempt) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalStateException("Document not found: " + documentId));
        DocumentStatus previous = document.getStatus();
        Instant now = Instant.now();

        document.setStatus(DocumentStatus.PROCESSED);
        document.setFailureReason(null);
        document.setExtractedResult(writeJson(extractedData));
        document.setRetryCount(attempt);
        document.setUpdatedAt(now);
        documentRepository.save(document);
        documentHistoryRepository.save(new DocumentHistory(documentId, DocumentStatus.PROCESSED, null, now));

        log.info("documentId={} attempt={} transition={}->PROCESSED", documentId, attempt, previous);
    }

    private void safelyMarkErrored(String documentId) {
        try {
            transition(documentId, DocumentStatus.FAILED, "ERROR", null);
        } catch (Exception inner) {
            log.error("documentId={} failed to record FAILED status after unexpected error", documentId, inner);
        }
    }

    private String writeJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize extracted result", e);
        }
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(properties.getRetryBackoffMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
