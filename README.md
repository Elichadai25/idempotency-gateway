# Idempotency Gateway – "Pay-Once" Protocol

A production-grade idempotency layer for **IgirePay Technologies Ltd.**, built with **Java 17** and **Spring Boot 3**.  
Ensures every payment is processed **exactly once**, no matter how many times a client retries.

---

## Architecture Diagram

```

Client (e-commerce shop)
        │
        │  POST /process-payment
        │  Header: Idempotency-Key: <uuid>
        │  Body:   { "amount": 100, "currency": "RWF" }
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     PaymentController                               │
│  Validates header presence + request body (Bean Validation)         │
└────────────────────────────┬────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     PaymentService                                  │
│                                                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │  IdempotencyStore.putIfAbsent(key, request)  [atomic]       │   │
│   └───────────────────────┬─────────────────────────────────────┘   │
│                           │                                         │
│          ┌────────────────┴──────────────────┐                      │
│          │                                   │                      │
│    KEY NOT FOUND                       KEY EXISTS                   │
│    (new record)                              │                      │
│          │                    ┌──────────────┼──────────────┐       │
│          │              SAME BODY        SAME BODY     DIFF BODY    │
│          │              IN_FLIGHT        COMPLETED               │  │
│          │                 │                │               │       │
│          ▼                 ▼                ▼               ▼       │
│   ┌─────────────┐  ┌──────────────┐ ┌───────────┐  ┌───────────┐   │
│   │  Process    │  │ Block/Wait   │ │  Return   │  │  Return   │   │
│   │  Payment    │  │ (await       │ │  Cached   │  │   409     │   │
│   │  (2s delay) │  │  completion) │ │  Response │  │ Conflict  │   │
│   └──────┬──────┘  └──────┬───────┘ └─────┬─────┘  └───────────┘   │
│          │                │               │                         │
│          └────────────────┘               │                         │
│                  │                        │                         │
│                  ▼                        │                         │
│       ┌──────────────────┐                │                         │
│       │ Cache response + │                │                         │
│       │ markCompleted()  │                │                         │
│       │ notifyAll()      │                │                         │
│       └──────────────────┘                │                         │
│                  │                        │                         │
└──────────────────┼────────────────────────┼─────────────────────────┘
                   │                        │
                   ▼                        ▼
           201 Created               201 Created
           (fresh)                   X-Cache-Hit: true
```

### Sequence Diagram – Concurrent Duplicate Requests (Bonus)

```
Client A ──POST /process-payment──────────────────────────────────────▶ Server
                                  [creates IN_FLIGHT record]
                                  [starts 2s processing]

Client B ──POST /process-payment──────────────────────────────────────▶ Server
                                  [sees IN_FLIGHT record, same body]
                                  [calls awaitCompletion()]
                                  [blocks...]

                                  [2s later – Client A processing done]
                                  [markCompleted() → notifyAll()]

Client A  ◀────────────────────── 201 Created { "message": "Charged 100 RWF" }

Client B  ◀────────────────────── 201 Created { ... } X-Cache-Hit: true
                                  [woke up, returned cached result]


```

---
## Setup Instructions

### Prerequisites

| Tool   | Version |
|--------|---------|
| Java   | 17+     |
| Maven  | 3.8+    |

### Run the server

```bash
# Clone your fork
git clone https://github.com/<your-username>/idempotency-gateway.git
cd idempotency-gateway

# Build and start
mvn spring-boot:run
```

The server starts on **http://localhost:8080**.

### Run tests

```bash
mvn test
```

> **Note:** The integration tests include the 2-second processing delay; expect the test suite to take ~20 seconds.

---


## API Documentation

### `POST /process-payment`

Processes a payment request. Idempotent – safe to retry with the same key.

#### Request Headers

| Header            | Required | Description                              |
|-------------------|----------|------------------------------------------|
| `Idempotency-Key` | ✅ Yes   | Client-generated unique string (e.g. UUID) |
| `Content-Type`    | ✅ Yes   | `application/json`                       |

#### Request Body

```json
{
  "amount": 100,
  "currency": "RWF"
}

```
| Field      | Type   | Constraints             |
|------------|--------|-------------------------|
| `amount`   | number | Required, > 0           |
| `currency` | string | Required, non-blank     |

#### Responses

| Scenario                        | Status | Body / Header                                |
|---------------------------------|--------|----------------------------------------------|
| First request (processed)       | `201`  | `{ status, message, transactionId, processedAt }` |
| Duplicate – same body (cached)  | `201`  | Same body as first + `X-Cache-Hit: true`     |
| Duplicate – different body      | `409`  | `{ error, message }`                         |
| Missing `Idempotency-Key`       | `400`  | `{ error, message }`                         |
| Invalid payload                 | `422`  | `{ error, message }`                         |
| In-flight wait timed out        | `504`  | `{ error, message }`                         |

#### Example – First Request

```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 100, "currency": "RWF"}'

```
```json
HTTP/1.1 201 Created

{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "a3f2c1d0-...",
  "processedAt": "2026-05-22T10:00:00Z"
}

```
#### Example – Duplicate Request (same key & body)

```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 100, "currency": "RWF"}'

```
```json
HTTP/1.1 201 Created
X-Cache-Hit: true

{
  "status": "SUCCESS",
  "message": "Charged 100 RWF",
  "transactionId": "a3f2c1d0-...",
  "processedAt": "2026-05-22T10:00:00Z"
}

```
#### Example – Conflict (different body)

```bash
curl -X POST http://localhost:8080/process-payment \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{"amount": 500, "currency": "RWF"}'

```
```json
HTTP/1.1 409 Conflict

{
  "status": 409,
  "error": "Conflict",
  "message": "Idempotency key already used for a different request body.",
  "timestamp": "2026-05-22T10:00:05Z"
}
```
```


### `GET /admin/audit-log`

Returns every payment attempt ever recorded (Developer's Choice feature).

```bash
curl http://localhost:8080/admin/audit-log
```

```json
[
  {
    "idempotencyKey": "550e8400-...",
    "transactionId": "a3f2c1d0-...",
    "outcome": "PROCESSED",
    "cacheHit": false,
    "recordedAt": "2026-05-22T10:00:00Z"
  },
  {
    "idempotencyKey": "550e8400-...",
    "transactionId": "a3f2c1d0-...",
    "outcome": "CACHE_HIT",
    "cacheHit": true,
    "recordedAt": "2026-05-22T10:00:03Z"
  }
]

```
### `GET /admin/stats`

Returns a quick summary of current system state.

### `GET /actuator/health`

Spring Boot health check endpoint.

---

## Design Decisions

### 1. `ConcurrentHashMap` + `putIfAbsent` for Atomic First-Write

The core idempotency guarantee relies on atomically determining who is "first" for a given key. Using `ConcurrentHashMap.putIfAbsent()` means the check-and-insert is a single atomic operation, so two threads arriving simultaneously for the same key will never both believe they are the first.

### 2. `synchronized` + `wait()/notifyAll()` for In-Flight Wait (Bonus)

When a second request arrives while the first is still processing (the IN_FLIGHT state), it calls `IdempotencyRecord.awaitCompletion()`. This uses Java's built-in object monitor (`wait()` / `notifyAll()`) — no extra libraries needed — and correctly handles spurious wakeups via a `while` loop. The timeout prevents infinite blocking if the original thread crashes.

### 3. `PaymentRequest.equals()` for Body Comparison (User Story 3)

Rather than hashing the raw JSON string (which would break on key reordering), equals() compares the parsed fields (`amount` and `currency`) directly. This is more robust and semantically correct.

### 4. No External Dependencies

The spec allows any store. Using a `ConcurrentHashMap` keeps the project self-contained — no Redis or database setup needed to run it. In production, the `IdempotencyStore` interface would be backed by Redis with a TTL.

---

## Developer's Choice Feature: Idempotency Key TTL + Audit Log

### What was added

1. **Key TTL Expiry (`AuditAndExpiryService`)** – A background job (via `@Scheduled`) runs every 60 seconds and evicts keys older than 24 hours (configurable via `idempotency.ttl-hours`).

2. **Audit Log** – Every payment attempt (processed or cache hit) is recorded with its key, transaction ID, outcome, and timestamp. Exposed via `GET /admin/audit-log`.

### Why this matters for a real Fintech company

**Key Expiry:**  
Payment processors like Stripe and PayPal expire idempotency keys after 24 hours. Without TTL:
- Memory grows unboundedly — a denial-of-service risk.
- A client bug could accidentally reuse a year-old key and silently receive a stale cached response instead of triggering a new payment.
- It becomes impossible to reuse a UUID after a long time, even legitimately.

**Audit Log:**  
Financial regulators (BNR in Rwanda, PCI-DSS globally) require an immutable audit trail of every payment attempt. The log answers: *"Did we charge this customer? When? Was it a retry?"* This is essential for dispute resolution and compliance. In production this would be written to an append-only database table or event stream.

---

## Project Structure
---
src/
├── main/java/com/igirepay/gateway/
│   ├── IdempotencyGatewayApplication.java   # Entry point
│   ├── controller/
│   │   ├── PaymentController.java           # POST /process-payment
│   │   └── AdminController.java             # GET /admin/audit-log, /stats
│   ├── service/
│   │   ├── PaymentService.java              # Core idempotency logic
│   │   ├── IdempotencyStore.java            # Thread-safe in-memory store
│   │   └── AuditAndExpiryService.java       # TTL + audit (Developer's Choice)
│   ├── model/
│   │   ├── PaymentRequest.java              # Request DTO
│   │   ├── PaymentResponse.java             # Response DTO
│   │   ├── IdempotencyRecord.java           # Per-key state (IN_FLIGHT / COMPLETED)
│   │   └── ErrorResponse.java              # Error envelope
│   └── exception/
│       ├── ConflictingRequestException.java
│       ├── InFlightTimeoutException.java
│       └── GlobalExceptionHandler.java      # Maps exceptions → HTTP responses
└── test/java/com/igirepay/gateway/
└── PaymentControllerIntegrationTest.java # Full integration tests||---

