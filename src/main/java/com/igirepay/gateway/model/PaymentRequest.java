package com.igirepay.gateway.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Represents an incoming payment request body.
 */
public class PaymentRequest {

    @NotNull(message = "amount is required")
    @DecimalMin(value = "0.01", message = "amount must be greater than zero")
    private final BigDecimal amount;

    @NotBlank(message = "currency is required")
    private final String currency;

    @JsonCreator
    public PaymentRequest(
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public BigDecimal getAmount() { return amount; }
    public String getCurrency()  { return currency; }

    /**
     * Used to detect whether two requests with the same idempotency key
     * carry different payloads (User Story 3 – fraud/error check).
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentRequest other)) return false;
        return Objects.equals(amount, other.amount)
                && Objects.equals(currency, other.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return "PaymentRequest{amount=" + amount + ", currency='" + currency + "'}";
    }
}
