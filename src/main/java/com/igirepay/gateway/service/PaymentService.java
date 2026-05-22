package com.igirepay.gateway.service;

import com.igirepay.gateway.exception.ConflictingRequestException;
import com.igirepay.gateway.exception.InFlightTimeoutException;
import com.igirepay.gateway.model.IdempotencyRecord;
import com.igirepay.gateway.model.IdempotencyRecord.State;
import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.model.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Orchestrates the entire idempotency flow:
 *
 *  1. First request   → process payment, cache result.
 *  2. Duplicate key + same body  → return cached result immediately.
 *  3. Duplicate key + diff body  → 409 Conflict.
 *  4. In-flight duplicate        → block until first request finishes (Bonus).
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** How long we simulate payment processing (2 s as per spec). */
    private static final long PROCESSING_DELAY_MS = 2_000L;

    /** Max time a waiting duplicate will block for an in-flight result. */
    private static final long IN_FLIGHT_WAIT_TIMEOUT_MS = 10_000L;

    private final IdempotencyStore     store;
    private final AuditAndExpiryService auditService;

    public PaymentService(IdempotencyStore store,
                          @Lazy AuditAndExpiryService auditService) {
        this.store        = store;
        this.auditService = auditService;
    }

    /**
     * Main entry point called by the controller.
     *
     * @return a {@link ProcessingResult} containing the response,
     *         HTTP status, and whether it was served from cache.
     */
    public ProcessingResult process(String idempotencyKey, PaymentRequest request) {

        // ── Atomic insert: returns existing record OR the brand-new one ──
        IdempotencyRecord record = store.putIfAbsent(idempotencyKey, request);
        boolean isNewRecord      = record.getState() == State.IN_FLIGHT
                                   && record.getOriginalRequest() == request; // identity check

        // ── Case 1: We just created this record → we are the "first" ──────
        if (isNewRecord) {
            return processFirstRequest(record, request);
        }

        // ── The key already existed; inspect it ───────────────────────────

        // Case 3: Same key, different body → 409 Conflict
        if (!record.getOriginalRequest().equals(request)) {
            log.warn("Idempotency key '{}' reused with different payload.", idempotencyKey);
            throw new ConflictingRequestException(
                    "Idempotency key already used for a different request body.");
        }

        // Case 4 (Bonus): Same key, same body, but still IN_FLIGHT
        if (record.getState() == State.IN_FLIGHT) {
            log.info("Key '{}' is IN_FLIGHT – waiting for original to complete.", idempotencyKey);
            return waitForInFlight(record);
        }

        // Case 2: Same key, same body, COMPLETED → return cached response
        log.info("Cache hit for idempotency key '{}'.", idempotencyKey);
        auditService.record(idempotencyKey,
                record.getCachedResponse().getTransactionId(), "CACHE_HIT", true);
        return new ProcessingResult(
                record.getCachedResponse(),
                record.getCachedHttpStatus(),
                true);
    }

    /* ------------------------------------------------------------------ */
    /*  Private helpers                                                     */
    /* ------------------------------------------------------------------ */

    private ProcessingResult processFirstRequest(IdempotencyRecord record,
                                                  PaymentRequest request) {
        log.info("Processing first request for key '{}'.", record.getIdempotencyKey());
        try {
            // Simulate 2-second payment processing
            Thread.sleep(PROCESSING_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String transactionId = UUID.randomUUID().toString();
        String message = String.format("Charged %s %s",
                request.getAmount().stripTrailingZeros().toPlainString(),
                request.getCurrency().toUpperCase());

        PaymentResponse response = new PaymentResponse(
                "SUCCESS",
                message,
                transactionId,
                Instant.now());

        int httpStatus = 201;
        record.markCompleted(response, httpStatus);

        log.info("Key '{}' processed → txId={}", record.getIdempotencyKey(), transactionId);
        auditService.record(record.getIdempotencyKey(), transactionId, "PROCESSED", false);
        return new ProcessingResult(response, httpStatus, false);
    }

    /**
     * Bonus: block the duplicate request until the in-flight one finishes.
     */
    private ProcessingResult waitForInFlight(IdempotencyRecord record) {
        try {
            record.awaitCompletion(IN_FLIGHT_WAIT_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (record.getState() == State.IN_FLIGHT) {
            // Timed out – the original processing never completed
            throw new InFlightTimeoutException(
                    "Original request did not complete within the allowed window.");
        }

        log.info("Key '{}' in-flight wait finished – returning cached result.",
                record.getIdempotencyKey());
        return new ProcessingResult(
                record.getCachedResponse(),
                record.getCachedHttpStatus(),
                true);
    }

    /* ------------------------------------------------------------------ */
    /*  Result carrier                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Simple value object carrying the result of a processing call.
     */
    public record ProcessingResult(
            PaymentResponse response,
            int             httpStatus,
            boolean         cacheHit) {}
}
