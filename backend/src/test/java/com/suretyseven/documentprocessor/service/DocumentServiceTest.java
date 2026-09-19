package com.suretyseven.documentprocessor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.documentprocessor.domain.Document;
import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.domain.DocumentType;
import com.suretyseven.documentprocessor.dto.UploadResult;
import com.suretyseven.documentprocessor.exception.DocumentConflictException;
import com.suretyseven.documentprocessor.exception.DocumentNotFoundException;
import com.suretyseven.documentprocessor.exception.DocumentNotRetryableException;
import com.suretyseven.documentprocessor.exception.InvalidDocumentTypeException;
import com.suretyseven.documentprocessor.exception.UnsupportedFileFormatException;
import com.suretyseven.documentprocessor.repository.DocumentHistoryRepository;
import com.suretyseven.documentprocessor.repository.DocumentRepository;
import com.suretyseven.documentprocessor.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the synchronous upload flow (assignment section 13, cases 1 and 6). */
class DocumentServiceTest {

    private DocumentRepository documentRepository;
    private DocumentHistoryRepository documentHistoryRepository;
    private FileStorage fileStorage;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(DocumentRepository.class);
        documentHistoryRepository = mock(DocumentHistoryRepository.class);
        fileStorage = mock(FileStorage.class);
        service = new DocumentService(documentRepository, documentHistoryRepository, fileStorage, new ObjectMapper());
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "statement.pdf", "application/pdf", "%PDF-1.4 fake".getBytes());
    }

    @Test
    void validUpload_createsUploadedDocumentAndHistoryRow() {
        when(documentRepository.findByContentHash(anyString())).thenReturn(Optional.empty());
        when(fileStorage.write(anyString(), any(), anyString())).thenReturn("./uploaded-documents/hash.pdf");

        UploadResult result = service.upload(pdfFile(), "FINANCIAL_STATEMENT", null);

        assertThat(result.status()).isEqualTo(DocumentStatus.UPLOADED);
        assertThat(result.documentId()).startsWith("DOC-");
        verify(documentRepository, times(1)).save(any(Document.class));
        verify(documentHistoryRepository, times(1)).save(any());
        verify(fileStorage, times(1)).write(anyString(), any(), anyString());
    }

    @Test
    void unknownDocumentType_rejectedWithoutTouchingStorageOrRepository() {
        assertThatThrownBy(() -> service.upload(pdfFile(), "NOT_A_TYPE", null))
                .isInstanceOf(InvalidDocumentTypeException.class);

        verify(fileStorage, never()).write(anyString(), any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void nonPdfFile_rejectedWithoutTouchingStorageOrRepository() {
        MockMultipartFile file = new MockMultipartFile("file", "statement.xlsx",
                "application/vnd.ms-excel", "data".getBytes());

        assertThatThrownBy(() -> service.upload(file, "FINANCIAL_STATEMENT", null))
                .isInstanceOf(UnsupportedFileFormatException.class);

        verify(fileStorage, never()).write(anyString(), any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void duplicateUpload_activeStatus_returns409WithoutCreatingSecondRow() {
        Document existing = existingDocument(DocumentStatus.UPLOADED, null);
        when(documentRepository.findByContentHash(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upload(pdfFile(), "FINANCIAL_STATEMENT", null))
                .isInstanceOf(DocumentConflictException.class);

        verify(fileStorage, never()).write(anyString(), any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void duplicateUpload_retryableFailed_reTriggersOnSameDocumentIdWithoutNewRow() {
        Document existing = existingDocument(DocumentStatus.FAILED, "TIMEOUT");
        when(documentRepository.findByContentHash(anyString())).thenReturn(Optional.of(existing));

        UploadResult result = service.upload(pdfFile(), "FINANCIAL_STATEMENT", null);

        assertThat(result.documentId()).isEqualTo(existing.getDocumentId());
        assertThat(result.status()).isEqualTo(DocumentStatus.PROCESSING);
        verify(fileStorage, never()).write(anyString(), any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void duplicateUpload_nonRetryableFailed_returns409() {
        Document existing = existingDocument(DocumentStatus.FAILED, "VALIDATION_FAILED: companyName is required");
        when(documentRepository.findByContentHash(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upload(pdfFile(), "FINANCIAL_STATEMENT", null))
                .isInstanceOf(DocumentConflictException.class);

        verify(fileStorage, never()).write(anyString(), any(), anyString());
        verify(documentRepository, never()).save(any());
    }

    @Test
    void retry_documentNotFound_throwsNotFound() {
        when(documentRepository.findById("DOC-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry("DOC-missing"))
                .isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void retry_statusNotFailed_throwsNotRetryable() {
        Document existing = existingDocument(DocumentStatus.PROCESSED, null);
        when(documentRepository.findById(existing.getDocumentId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.retry(existing.getDocumentId()))
                .isInstanceOf(DocumentNotRetryableException.class);
    }

    @Test
    void retry_nonRetryableFailureReason_throwsNotRetryable() {
        Document existing = existingDocument(DocumentStatus.FAILED, "VALIDATION_FAILED: companyName is required");
        when(documentRepository.findById(existing.getDocumentId())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.retry(existing.getDocumentId()))
                .isInstanceOf(DocumentNotRetryableException.class);
    }

    @Test
    void retry_retryableFailureReason_returnsProcessingOnSameId() {
        Document existing = existingDocument(DocumentStatus.FAILED, "TIMEOUT");
        when(documentRepository.findById(existing.getDocumentId())).thenReturn(Optional.of(existing));

        UploadResult result = service.retry(existing.getDocumentId());

        assertThat(result.documentId()).isEqualTo(existing.getDocumentId());
        assertThat(result.status()).isEqualTo(DocumentStatus.PROCESSING);
    }

    private Document existingDocument(DocumentStatus status, String failureReason) {
        Document doc = new Document();
        doc.setDocumentId("DOC-existing");
        doc.setFilename("statement.pdf");
        doc.setDocumentType(DocumentType.FINANCIAL_STATEMENT);
        doc.setContentHash("existing-hash");
        doc.setFilePath("./uploaded-documents/existing-hash.pdf");
        doc.setStatus(status);
        doc.setFailureReason(failureReason);
        doc.setCreatedAt(Instant.now());
        doc.setUpdatedAt(Instant.now());
        return doc;
    }
}
