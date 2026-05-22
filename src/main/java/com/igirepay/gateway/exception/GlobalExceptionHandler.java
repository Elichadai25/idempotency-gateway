package com.igirepay.gateway.exception;

import com.igirepay.gateway.model.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralised exception → HTTP response mapping for the entire API.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Duplicate key with different payload → 409 Conflict */
    @ExceptionHandler(ConflictingRequestException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictingRequestException ex) {
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(
                        HttpStatus.CONFLICT.value(),
                        "Conflict",
                        ex.getMessage()));
    }

    /** In-flight wait timed out → 504 Gateway Timeout */
    @ExceptionHandler(InFlightTimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(InFlightTimeoutException ex) {
        return ResponseEntity
                .status(HttpStatus.GATEWAY_TIMEOUT)
                .body(new ErrorResponse(
                        HttpStatus.GATEWAY_TIMEOUT.value(),
                        "Gateway Timeout",
                        ex.getMessage()));
    }

    /** Missing Idempotency-Key header → 400 Bad Request */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        HttpStatus.BAD_REQUEST.value(),
                        "Bad Request",
                        "Required header '" + ex.getHeaderName() + "' is missing."));
    }

    /** Bean Validation failures → 422 Unprocessable Entity */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult()
                           .getFieldErrors()
                           .stream()
                           .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                           .reduce((a, b) -> a + "; " + b)
                           .orElse("Validation error");
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse(
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        "Unprocessable Entity",
                        details));
    }

    /** Catch-all → 500 Internal Server Error */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        "Internal Server Error",
                        "An unexpected error occurred."));
    }
}

