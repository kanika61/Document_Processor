package com.suretyseven.documentprocessor.exception;

/** Raised by POST /documents/{id}/retry when the document isn't FAILED with a retryable reason. */
public class DocumentNotRetryableException extends RuntimeException {

    public DocumentNotRetryableException(String message) {
        super(message);
    }
}
