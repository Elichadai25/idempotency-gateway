package com.igirepay.gateway.exception;

/**
 * Thrown when an in-flight duplicate request times out waiting for
 * the original to complete.
 */
public class InFlightTimeoutException extends RuntimeException {
    public InFlightTimeoutException(String message) {
        super(message);
    }
}
