package com.suretyseven.documentprocessor.processor;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resolves the {@link DocumentProcessor} for a given {@link FileType}. Adding a new format
 * later (e.g. an ExcelProcessor) means one new class + one new entry here — no changes to
 * DocumentProcessingService.
 */
@Component
public class DocumentProcessorRegistry {

    private final Map<FileType, DocumentProcessor> processors;

    public DocumentProcessorRegistry(PdfProcessor pdfProcessor) {
        this.processors = Map.of(FileType.PDF, pdfProcessor);
    }

    public DocumentProcessor get(FileType fileType) {
        DocumentProcessor processor = processors.get(fileType);
        if (processor == null) {
            throw new IllegalStateException("No DocumentProcessor registered for file type: " + fileType);
        }
        return processor;
    }
}
