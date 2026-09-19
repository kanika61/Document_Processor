package com.suretyseven.documentprocessor.exception;

import java.util.Map;

/** Raised for the 409 branches of the upload flow (duplicate content hash). */
public class DocumentConflictException extends RuntimeException {

    private final Map<String, Object> body;

    public DocumentConflictException(String message, Map<String, Object> body) {
        super(message);
        this.body = body;
    }

    public Map<String, Object> getBody() {
        return body;
    }
}
