package com.igirepay.gateway.model;

import java.time.Instant;

/**
 * Standard error envelope returned by the API on failure.
 */
public class ErrorResponse {

    private final int     status;
    private final String  error;
    private final String  message;
    private final Instant timestamp;

    public ErrorResponse(int status, String error, String message) {
        this.status    = status;
        this.error     = error;
        this.message   = message;
        this.timestamp = Instant.now();
    }

    public int     getStatus()    { return status; }
    public String  getError()     { return error; }
    public String  getMessage()   { return message; }
    public Instant getTimestamp() { return timestamp; }
}
