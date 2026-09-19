package com.suretyseven.documentprocessor.processor;

/**
 * One implementation per file FORMAT (not per document type — see the note on this
 * distinction in the implementation plan). {@link PdfProcessor} is the only real
 * implementation needed for this assignment.
 */
public interface DocumentProcessor {

    ProcessResult process(byte[] fileBytes, String documentId);
}
