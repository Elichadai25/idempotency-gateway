package com.igirepay.gateway.model;

import java.time.Instant;

/**
 * Internal record that the idempotency store keeps for every key it has seen.
 *
 * States:
 *   IN_FLIGHT  – a request is currently being processed (used for race-condition guard).
 *   COMPLETED  – processing finished and the response is cached.
 */
public class IdempotencyRecord {

    public enum State { IN_FLIGHT, COMPLETED }

    private final String          idempotencyKey;
    private final PaymentRequest  originalRequest;
    private final Instant         createdAt;

    private volatile State          state;
    private volatile PaymentResponse cachedResponse;
    private volatile int             cachedHttpStatus;

    public IdempotencyRecord(String idempotencyKey, PaymentRequest originalRequest) {
        this.idempotencyKey  = idempotencyKey;
        this.originalRequest = originalRequest;
        this.createdAt       = Instant.now();
        this.state           = State.IN_FLIGHT;
    }

    /* ------------------------------------------------------------------ */
    /*  Accessors                                                           */
    /* ------------------------------------------------------------------ */

    public String          getIdempotencyKey()  { return idempotencyKey; }
    public PaymentRequest  getOriginalRequest() { return originalRequest; }
    public Instant         getCreatedAt()       { return createdAt; }
    public State           getState()           { return state; }
    public PaymentResponse getCachedResponse()  { return cachedResponse; }
    public int             getCachedHttpStatus() { return cachedHttpStatus; }

    /* ------------------------------------------------------------------ */
    /*  Mutators (called only after processing completes)                  */
    /* ------------------------------------------------------------------ */

    public synchronized void markCompleted(PaymentResponse response, int httpStatus) {
        this.cachedResponse  = response;
        this.cachedHttpStatus = httpStatus;
        this.state           = State.COMPLETED;
        // Wake up any threads that were waiting on this record (Bonus story).
        notifyAll();
    }

    /**
     * Blocking wait used by concurrent duplicate requests (race-condition bonus).
     * Waits until the IN_FLIGHT record transitions to COMPLETED.
     *
     * @param timeoutMillis maximum wait time
     */
    public synchronized void awaitCompletion(long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (state == State.IN_FLIGHT) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) break;
            wait(remaining);
        }
    }
}
