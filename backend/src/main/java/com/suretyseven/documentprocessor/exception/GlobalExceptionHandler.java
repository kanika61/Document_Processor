package com.suretyseven.documentprocessor.exception;

import com.suretyseven.documentprocessor.dto.ErrorResponse;
import com.suretyseven.documentprocessor.storage.FileStorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;

/**
 * Handles UNEXPECTED failures (DB connectivity, uncaught exceptions, disk I/O) with a generic,
 * non-leaking 500 body. EXPECTED business-flow outcomes (400 invalid input, 409 duplicates) get
 * their own specific handlers here too, but with the deliberate messages the flow defines —
 * never the generic fallback.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final ErrorResponse GENERIC_ERROR =
            new ErrorResponse("Something went wrong. Please try again later.");

    @ExceptionHandler(InvalidDocumentTypeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDocumentType(InvalidDocumentTypeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(UnsupportedFileFormatException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedFileFormat(UnsupportedFileFormatException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(DocumentNotRetryableException.class)
    public ResponseEntity<ErrorResponse> handleNotRetryable(DocumentNotRetryableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(DocumentConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(DocumentConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getBody());
    }

    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(DocumentNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Invalid value for parameter '" + e.getName() + "'"));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("Missing required part '" + e.getRequestPartName() + "'"));
    }

    @ExceptionHandler(FileStorageException.class)
    public ResponseEntity<ErrorResponse> handleFileStorage(FileStorageException e) {
        log.error("File storage failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Upload failed, please try again"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(GENERIC_ERROR);
    }
}
