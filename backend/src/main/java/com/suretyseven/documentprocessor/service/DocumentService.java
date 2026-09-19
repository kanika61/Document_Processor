package com.suretyseven.documentprocessor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.documentprocessor.domain.Document;
import com.suretyseven.documentprocessor.domain.DocumentHistory;
import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.domain.DocumentType;
import com.suretyseven.documentprocessor.dto.DocumentDetailResponse;
import com.suretyseven.documentprocessor.dto.DocumentHistoryEntry;
import com.suretyseven.documentprocessor.dto.DocumentSummary;
import com.suretyseven.documentprocessor.dto.UploadResult;
import com.suretyseven.documentprocessor.exception.DocumentConflictException;
import com.suretyseven.documentprocessor.exception.DocumentNotFoundException;
import com.suretyseven.documentprocessor.exception.DocumentNotRetryableException;
import com.suretyseven.documentprocessor.exception.InvalidDocumentTypeException;
import com.suretyseven.documentprocessor.exception.UnsupportedFileFormatException;
import com.suretyseven.documentprocessor.processor.FileType;
import com.suretyseven.documentprocessor.repository.DocumentHistoryRepository;
import com.suretyseven.documentprocessor.repository.DocumentRepository;
import com.suretyseven.documentprocessor.storage.FileStorage;
import com.suretyseven.documentprocessor.util.HashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the SYNCHRONOUS upload-time flow: hashing, duplicate lookup, request-level validation,
 * disk write, and the initial DB insert/duplicate-branch logic. Does NOT trigger async
 * processing itself — the controller does that via DocumentProcessingService directly.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final DocumentHistoryRepository documentHistoryRepository;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    public DocumentService(DocumentRepository documentRepository,
                            DocumentHistoryRepository documentHistoryRepository,
                            FileStorage fileStorage,
                            ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.documentHistoryRepository = documentHistoryRepository;
        this.fileStorage = fileStorage;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UploadResult upload(MultipartFile file, String documentTypeRaw, Long providedSizeBytes) {
        DocumentType documentType = DocumentType.fromString(documentTypeRaw)
                .orElseThrow(() -> new InvalidDocumentTypeException(
                        "Unknown documentType '" + documentTypeRaw + "'. Supported types: "
                                + Arrays.toString(DocumentType.values())));

        String filename = file.getOriginalFilename();
        FileType fileType = FileType.fromFilename(filename)
                .orElseThrow(() -> new UnsupportedFileFormatException(
                        "Unsupported file format for '" + filename + "'. Only .pdf is supported."));

        byte[] bytes = readBytes(file);
        String contentHash = HashUtils.sha256Hex(bytes);

        Optional<Document> existing = documentRepository.findByContentHash(contentHash);
        if (existing.isPresent()) {
            return handleDuplicate(existing.get());
        }

        if (providedSizeBytes != null && providedSizeBytes != bytes.length) {
            log.warn("Uploaded file size mismatch: reported={} actual={} filename={}",
                    providedSizeBytes, bytes.length, filename);
        }

        String filePath = fileStorage.write(contentHash, bytes, fileType.extension());

        String documentId = "DOC-" + UUID.randomUUID();
        Instant now = Instant.now();

        Document document = new Document();
        document.setDocumentId(documentId);
        document.setFilename(filename);
        document.setDocumentType(documentType);
        document.setContentHash(contentHash);
        document.setFilePath(filePath);
        document.setStatus(DocumentStatus.UPLOADED);
        document.setFileSizeBytes((long) bytes.length);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        documentRepository.save(document);

        documentHistoryRepository.save(new DocumentHistory(documentId, DocumentStatus.UPLOADED, null, now));

        log.info("documentId={} transition=CREATED->UPLOADED documentType={} filename={}",
                documentId, documentType, filename);

        return new UploadResult(documentId, DocumentStatus.UPLOADED);
    }

    private UploadResult handleDuplicate(Document existing) {
        DocumentStatus status = existing.getStatus();
        if (status == DocumentStatus.UPLOADED || status == DocumentStatus.PROCESSING
                || status == DocumentStatus.PROCESSED) {
            throw new DocumentConflictException("Duplicate document", conflictBody(
                    "A document with identical content already exists.", existing, null));
        }

        // status == FAILED
        if (isRetryable(existing)) {
            log.info("documentId={} duplicate upload of retryable FAILED document; re-triggering processing",
                    existing.getDocumentId());
            return new UploadResult(existing.getDocumentId(), DocumentStatus.PROCESSING);
        }

        throw new DocumentConflictException("Non-retryable duplicate", conflictBody(
                "This document cannot be uploaded",
                existing, existing.getFailureReason()));
    }

    private Map<String, Object> conflictBody(String message, Document existing, String failureReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", message);
        body.put("documentId", existing.getDocumentId());
        body.put("filename", existing.getFilename());
        body.put("status", existing.getStatus());
        body.put("uploadedAt", existing.getCreatedAt());
        if (failureReason != null) {
            body.put("failureReason", failureReason);
        }
        return body;
    }

    /** Mirrors the rule in the implementation plan: TIMEOUT/ERROR are flakiness, retryable. */
    private boolean isRetryable(Document doc) {
        if (doc.getStatus() != DocumentStatus.FAILED || doc.getFailureReason() == null) {
            return false;
        }
        return doc.getFailureReason().startsWith("TIMEOUT") || doc.getFailureReason().startsWith("ERROR");
    }

    /**
     * Explicit retry via POST /documents/{id}/retry. Re-uses the file already on disk
     * (FileStorage.read(doc.filePath) happens inside processDocumentAsync) — no new upload
     * needed. Does not itself trigger processing; the caller (DocumentController) does that via
     * DocumentProcessingService, same as the upload flow.
     */
    @Transactional
    public UploadResult retry(String documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        if (!isRetryable(document)) {
            throw new DocumentNotRetryableException("This document cannot be retried.");
        }
        return new UploadResult(documentId, DocumentStatus.PROCESSING);
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getDetail(String documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new DocumentNotFoundException(documentId));
        return new DocumentDetailResponse(
                document.getDocumentId(),
                document.getStatus(),
                document.getDocumentType(),
                document.getFilename(),
                document.getFileSizeBytes(),
                document.getCreatedAt(),
                document.getUpdatedAt(),
                parseExtractedResult(document.getExtractedResult()),
                document.getFailureReason());
    }

    @Transactional(readOnly = true)
    public List<DocumentHistoryEntry> getHistory(String documentId) {
        if (!documentRepository.existsById(documentId)) {
            throw new DocumentNotFoundException(documentId);
        }
        return documentHistoryRepository.findByDocumentIdOrderByTimestampAsc(documentId).stream()
                .map(h -> new DocumentHistoryEntry(h.getStatus(), h.getReason(), h.getTimestamp()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<DocumentSummary> search(DocumentStatus status, DocumentType documentType,
                                         LocalDate uploadDate, Pageable pageable) {
        Instant uploadDateStart = uploadDate == null ? null : uploadDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant uploadDateEnd = uploadDate == null ? null : uploadDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        return documentRepository.search(status, documentType, uploadDateStart, uploadDateEnd, pageable)
                .map(d -> new DocumentSummary(
                        d.getDocumentId(),
                        d.getFilename(),
                        d.getDocumentType(),
                        d.getStatus(),
                        d.getFileSizeBytes(),
                        d.getFailureReason(),
                        d.getCreatedAt(),
                        d.getUpdatedAt()));
    }

    private Map<String, Object> parseExtractedResult(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse stored extracted result", e);
        }
    }
}
