package com.igirepay.gateway.service;

import com.igirepay.gateway.model.IdempotencyRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ──────────────────────────────────────────────────────────────────────
 *  DEVELOPER'S CHOICE FEATURE: Idempotency Key TTL + Audit Log
 * ──────────────────────────────────────────────────────────────────────
 *
 * WHY THIS MATTERS IN A REAL FINTECH SYSTEM:
 *
 * 1. Key Expiry (TTL)
 *    Idempotency keys must not live forever. Stripe, PayPal, and other
 *    payment processors expire keys after 24 hours.  Without expiry:
 *      • Memory grows unbounded (DoS risk).
 *      • A key generated months ago could be accidentally reused by a
 *        client bug, silently returning a stale result.
 *    This service runs a background job every minute that evicts records
 *    older than the configured TTL (default 24 h).
 *
 * 2. Audit Log
 *    Regulators (e.g. BNR in Rwanda, PCI-DSS globally) require a trail of
 *    every payment attempt.  This simple in-memory log records when a key
 *    was first seen, what happened, and whether it was a cache hit.
 *    In production this would write to a database / append-only ledger.
 */
@Service
@EnableScheduling
public class AuditAndExpiryService {

    private static final Logger log = LoggerFactory.getLogger(AuditAndExpiryService.class);

    /** TTL for idempotency records in milliseconds (default 24 h). */
    @Value("${idempotency.ttl-hours:24}")
    private long ttlHours;

    private final IdempotencyStore store;

    /** Simple in-memory audit log (transaction-id → audit entry). */
    private final List<AuditEntry> auditLog = new ArrayList<>();
    private final Object auditLock = new Object();

    /** Tracks creation time so we can expire records. */
    private final ConcurrentHashMap<String, Instant> keyCreatedAt =
            new ConcurrentHashMap<>();

    public AuditAndExpiryService(IdempotencyStore store) {
        this.store = store;
    }

    /* ------------------------------------------------------------------ */
    /*  Audit log                                                           */
    /* ------------------------------------------------------------------ */

    public void record(String idempotencyKey, String transactionId,
                       String outcome, boolean cacheHit) {
        synchronized (auditLock) {
            auditLog.add(new AuditEntry(idempotencyKey, transactionId,
                                        outcome, cacheHit, Instant.now()));
        }
        keyCreatedAt.putIfAbsent(idempotencyKey, Instant.now());
        log.info("AUDIT key={} txId={} outcome={} cacheHit={}",
                idempotencyKey, transactionId, outcome, cacheHit);
    }

    /** Returns a snapshot of the audit log (for the admin endpoint). */
    public List<AuditEntry> getAuditLog() {
        synchronized (auditLock) {
            return List.copyOf(auditLog);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  TTL expiry – runs every 60 seconds                                  */
    /* ------------------------------------------------------------------ */

    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredKeys() {
        long ttlMillis = ttlHours * 3_600_000L;
        Instant cutoff = Instant.now().minusMillis(ttlMillis);
        int evicted = 0;

        for (Map.Entry<String, Instant> entry : keyCreatedAt.entrySet()) {
            if (entry.getValue().isBefore(cutoff)) {
                keyCreatedAt.remove(entry.getKey());
                evicted++;
                log.debug("Evicted expired idempotency key '{}'.", entry.getKey());
            }
        }

        if (evicted > 0) {
            log.info("TTL eviction run complete: {} keys removed.", evicted);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Inner record                                                        */
    /* ------------------------------------------------------------------ */

    public record AuditEntry(
            String  idempotencyKey,
            String  transactionId,
            String  outcome,
            boolean cacheHit,
            Instant recordedAt) {}
}
