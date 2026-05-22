package com.igirepay.gateway.controller;

import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.model.PaymentResponse;
import com.igirepay.gateway.service.PaymentService;
import com.igirepay.gateway.service.PaymentService.ProcessingResult;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Exposes the single payment endpoint required by the specification.
 *
 * POST /process-payment
 *   Headers:  Idempotency-Key: <unique-string>
 *   Body:     { "amount": 100, "currency": "RWF" }
 */
@RestController
@RequestMapping("/process-payment")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        ProcessingResult result = paymentService.process(idempotencyKey, request);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus());

        // Indicate cached / replayed responses (User Story 2)
        if (result.cacheHit()) {
            builder.header("X-Cache-Hit", "true");
        }

        return builder.body(result.response());
    }
}
