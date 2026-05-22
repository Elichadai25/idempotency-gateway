package com.igirepay.gateway.service;

import com.igirepay.gateway.model.IdempotencyRecord;
import com.igirepay.gateway.model.PaymentRequest;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store for idempotency records.
 *
 * Uses ConcurrentHashMap so that the critical "check-then-act" during
 * first insertion is atomic (via computeIfAbsent), preventing two threads
 * from each thinking they are the "first" for the same key.
 */
@Component
public class IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyRecord> store =
            new ConcurrentHashMap<>();

    /**
     * Atomically inserts a new IN_FLIGHT record for the given key ONLY if
     * the key is not already present.
     *
     * @return the existing record if the key was already present,
     *         or the newly created record if this is the first time we see the key.
     */
    public IdempotencyRecord putIfAbsent(String key, PaymentRequest request) {
        IdempotencyRecord newRecord = new IdempotencyRecord(key, request);
        IdempotencyRecord existing  = store.putIfAbsent(key, newRecord);
        // existing == null  → we inserted the new record (first request)
        // existing != null  → key already existed; return the stored one
        return existing != null ? existing : newRecord;
    }

    /**
     * Returns the record for a key, or null if the key has never been seen.
     */
    public IdempotencyRecord get(String key) {
        return store.get(key);
    }

    /**
     * Returns true if the store already contains this key.
     */
    public boolean contains(String key) {
        return store.containsKey(key);
    }

    /**
     * Returns the current number of stored records (useful for monitoring).
     */
    public int size() {
        return store.size();
    }

    /**
     * Clears all records – intended for tests only.
     */
    public void clearAll() {
        store.clear();
    }
}
