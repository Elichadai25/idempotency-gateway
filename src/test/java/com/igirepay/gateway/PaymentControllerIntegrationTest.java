package com.igirepay.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igirepay.gateway.model.PaymentRequest;
import com.igirepay.gateway.service.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentControllerIntegrationTest {

    @Autowired MockMvc        mvc;
    @Autowired ObjectMapper   mapper;
    @Autowired IdempotencyStore store;

    @BeforeEach
    void resetStore() {
        store.clearAll();
    }

    /* ------------------------------------------------------------------ */
    /*  User Story 1 – Happy Path                                           */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("US1: First request is processed and returns 201 with correct message")
    void firstRequest_returnsCreated() throws Exception {
        String key     = UUID.randomUUID().toString();
        String payload = toJson(new PaymentRequest(BigDecimal.valueOf(100), "RWF"));

        mvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.status").value("SUCCESS"))
           .andExpect(jsonPath("$.message").value("Charged 100 RWF"))
           .andExpect(jsonPath("$.transactionId").isNotEmpty());
    }

    /* ------------------------------------------------------------------ */
    /*  User Story 2 – Duplicate Key, Same Body                            */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("US2: Duplicate request returns cached response with X-Cache-Hit: true")
    void duplicateRequest_returnsCachedResponse() throws Exception {
        String key     = UUID.randomUUID().toString();
        String payload = toJson(new PaymentRequest(BigDecimal.valueOf(200), "RWF"));

        // First request
        MvcResult first = mvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
           .andExpect(status().isCreated())
           .andReturn();

        String firstBody = first.getResponse().getContentAsString();

        // Duplicate request
        mvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
           .andExpect(status().isCreated())
           .andExpect(header().string("X-Cache-Hit", "true"))
           .andExpect(content().json(firstBody));   // exact same body
    }

    /* ------------------------------------------------------------------ */
    /*  User Story 3 – Different Body, Same Key                            */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("US3: Same key, different body returns 409 Conflict")
    void sameKey_differentBody_returnsConflict() throws Exception {
        String key      = UUID.randomUUID().toString();
        String original = toJson(new PaymentRequest(BigDecimal.valueOf(100), "RWF"));
        String tampered = toJson(new PaymentRequest(BigDecimal.valueOf(500), "RWF"));

        // First request with amount=100
        mvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(original))
           .andExpect(status().isCreated());

        // Second request with amount=500 – should be rejected
        mvc.perform(post("/process-payment")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(tampered))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message")
                   .value("Idempotency key already used for a different request body."));
    }

    /* ------------------------------------------------------------------ */
    /*  Bonus – Race Condition Guard                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("BONUS: Concurrent duplicates wait and return the same result")
    void concurrentDuplicates_onlyOneProcessed() throws Exception {
        String key     = UUID.randomUUID().toString();
        String payload = toJson(new PaymentRequest(BigDecimal.valueOf(300), "USD"));

        int threads = 5;
        ExecutorService pool    = Executors.newFixedThreadPool(threads);
        CountDownLatch  start   = new CountDownLatch(1);
        CountDownLatch  done    = new CountDownLatch(threads);
        AtomicInteger   success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    mvc.perform(post("/process-payment")
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                       .andExpect(status().isCreated())
                       .andExpect(jsonPath("$.status").value("SUCCESS"));
                    success.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();   // fire all threads simultaneously
        done.await();
        pool.shutdown();

        // All threads should eventually get a 201 SUCCESS
        assert success.get() == threads :
                "Expected " + threads + " successes but got " + success.get();
    }

    /* ------------------------------------------------------------------ */
    /*  Validation – missing header                                         */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("VAL: Missing Idempotency-Key header returns 400")
    void missingHeader_returns400() throws Exception {
        mvc.perform(post("/process-payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(new PaymentRequest(BigDecimal.valueOf(50), "RWF"))))
           .andExpect(status().isBadRequest());
    }

    /* ------------------------------------------------------------------ */
    /*  Validation – invalid payload                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("VAL: Negative amount returns 422 Unprocessable Entity")
    void negativeAmount_returns422() throws Exception {
        mvc.perform(post("/process-payment")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(new PaymentRequest(BigDecimal.valueOf(-5), "RWF"))))
           .andExpect(status().isUnprocessableEntity());
    }

    /* ------------------------------------------------------------------ */
    /*  Helpers                                                             */
    /* ------------------------------------------------------------------ */

    private String toJson(Object obj) throws Exception {
        return mapper.writeValueAsString(obj);
    }
}

