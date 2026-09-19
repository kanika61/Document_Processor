package com.suretyseven.documentprocessor.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suretyseven.documentprocessor.domain.DocumentStatus;
import com.suretyseven.documentprocessor.domain.DocumentType;
import com.suretyseven.documentprocessor.dto.DocumentDetailResponse;
import com.suretyseven.documentprocessor.dto.DocumentHistoryEntry;
import com.suretyseven.documentprocessor.dto.DocumentSummary;
import com.suretyseven.documentprocessor.dto.UploadResponse;
import com.suretyseven.documentprocessor.dto.UploadResult;
import com.suretyseven.documentprocessor.service.DocumentProcessingService;
import com.suretyseven.documentprocessor.service.DocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/** REST endpoints only — no business logic; delegates to DocumentService / DocumentProcessingService. */
@RestController
public class DocumentController {

    private static final Logger log = LoggerFactory.getLogger(DocumentController.class);

    private final DocumentService documentService;
    private final DocumentProcessingService documentProcessingService;
    private final ObjectMapper objectMapper;

    public DocumentController(DocumentService documentService,
                               DocumentProcessingService documentProcessingService,
                               ObjectMapper objectMapper) {
        this.documentService = documentService;
        this.documentProcessingService = documentProcessingService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("documentType") String documentType,
            @RequestParam(value = "metadata", required = false) String metadata) {

        Long sizeBytes = parseSizeBytes(metadata);

        UploadResult result = documentService.upload(file, documentType, sizeBytes);

        // Separate bean, called directly by the controller — never via DocumentService or
        // `this.`, to avoid silently bypassing the @Async proxy.
        documentProcessingService.processDocumentAsync(result.documentId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadResponse(result.documentId(), result.status()));
    }

    @PostMapping("/documents/{id}/retry")
    public ResponseEntity<UploadResponse> retry(@PathVariable("id") String id) {
        UploadResult result = documentService.retry(id);

        // Same separate-bean call as the upload flow — never via `this.` or DocumentService.
        documentProcessingService.processDocumentAsync(result.documentId());

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new UploadResponse(result.documentId(), result.status()));
    }

    @GetMapping("/documents/{id}")
    public DocumentDetailResponse getDocument(@PathVariable("id") String id) {
        return documentService.getDetail(id);
    }

    @GetMapping("/documents/{id}/history")
    public List<DocumentHistoryEntry> getHistory(@PathVariable("id") String id) {
        return documentService.getHistory(id);
    }

    @GetMapping("/documents")
    public Page<DocumentSummary> listDocuments(
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) DocumentType documentType,
            @RequestParam(required = false) LocalDate uploadDate,
            @PageableDefault(size = 20) Pageable pageable) {
        return documentService.search(status, documentType, uploadDate, pageable);
    }

    private Long parseSizeBytes(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadataJson);
            JsonNode sizeBytes = node.get("sizeBytes");
            return (sizeBytes != null && sizeBytes.isNumber()) ? sizeBytes.asLong() : null;
        } catch (Exception e) {
            log.warn("Ignoring unparseable metadata field: {}", metadataJson);
            return null;
        }
    }
}
