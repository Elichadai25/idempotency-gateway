package com.igirepay.gateway.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Represents the response returned after processing (or replaying) a payment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

    private final String status;
    private final String message;
    private final String transactionId;
    private final Instant processedAt;

    public PaymentResponse(String status, String message,
                           String transactionId, Instant processedAt) {
        this.status       = status;
        this.message      = message;
        this.transactionId = transactionId;
        this.processedAt  = processedAt;
    }

    public String  getStatus()        { return status; }
    public String  getMessage()       { return message; }
    public String  getTransactionId() { return transactionId; }
    public Instant getProcessedAt()   { return processedAt; }
}
