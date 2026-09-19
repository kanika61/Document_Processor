package com.suretyseven.documentprocessor.exception;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException(String documentId) {
        super("No document found with id " + documentId);
    }
}
