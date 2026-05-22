package com.igirepay.gateway.exception;

/**
 * Thrown when a client reuses an idempotency key with a different request body.
 */
public class ConflictingRequestException extends RuntimeException {
    public ConflictingRequestException(String message) {
        super(message);
    }
}
