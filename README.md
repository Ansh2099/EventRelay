# EventRelay

A reliable webhook ingestion and processing platform that provides database-backed deduplication, controlled retries, and clear visibility into event state and failures.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Key Features](#key-features)
- [Getting Started](#getting-started)
- [API Documentation](#api-documentation)
- [How It Works](#how-it-works)
- [Configuration](#configuration)
- [Testing](#testing)
- [Known Limitations & Tradeoffs](#known-limitations--tradeoffs)
- [Scaling Considerations](#scaling-considerations)

## Overview

EventRelay solves the common problem of unreliable webhook processing in modern backend systems. Third-party webhook providers (payment processors, video platforms, CRMs) often deliver events that are:

- **Retried aggressively** by providers
- **Delivered out of order**
- **Occasionally duplicated**
- **Sometimes malformed**
- **Difficult to debug** after partial failures

Naive webhook handlers process events synchronously and fail under retries, leading to duplicate side effects, inconsistent state, and silent data corruption.

EventRelay provides a production-ready solution that:
- ✅ Guarantees **exactly-once ingestion (deduplication)** per webhook event ID
- ✅ Safely handles retries without duplicate side effects
- ✅ Provides clear visibility into event lifecycle and failures (via query APIs + logs)
- ✅ Remains resilient to worker crashes and partial system failures

## Architecture

### High-Level Design

EventRelay follows a **monolithic backend architecture** with a **database-backed state machine** for event processing. The system prioritizes **correctness and debuggability** over raw throughput.

```
┌─────────────────┐
│  Webhook        │
│  Provider       │
└────────┬────────┘
         │ POST /webhooks/{source}
         ▼
┌─────────────────────────────────┐
│  Webhook Ingestion API          │
│  - Signature Verification       │
│  - Idempotency Check            │
│  - Event Persistence             │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  PostgreSQL (Event Store)        │
│  - Durable Event Storage        │
│  - State Machine                 │
│  - Unique Constraint Enforcement│
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  Async Processing Workers        │
│  - Poll for Eligible Events      │
│  - Process with Business Logic   │
│  - Handle Retries                │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│  Business Logic Handler          │
│  (Pluggable Interface)           │
└─────────────────────────────────┘
```

### Core Components

1. **Webhook Ingestion API** (`WebhookIngestionController`)
   - Accepts webhook payloads from third-party providers
   - Validates signatures using HMAC-SHA256
   - Enforces idempotency via database constraints
   - Returns HTTP 200 immediately upon acceptance

2. **Persistent Event Store** (PostgreSQL)
   - Stores all events with full lifecycle tracking
   - Enforces uniqueness via `(source, external_event_id)` constraint
   - Maintains state machine transitions atomically

3. **Async Processing Workers** (`WebhookEventWorker` + `WebhookEventProcessor`)
   - Polls for eligible events (RECEIVED or FAILED states)
   - Uses database row locking (`FOR UPDATE SKIP LOCKED`) for concurrency safety
   - Processes events through pluggable business logic handlers

4. **Retry Scheduler** (`RetryPolicy`)
   - Implements exponential backoff strategy
   - Configurable max retry count (default: 5)
   - Moves events to DEAD_LETTER after max retries

5. **Audit and Query APIs** (`EventsController`)
   - Provides visibility into event lifecycle
   - Enables debugging and operational monitoring

## Key Features

### 1. Asynchronous Processing

**Why asynchronous?** 

Synchronous webhook processing creates several problems:
- **Slow response times**: Business logic execution delays HTTP responses to webhook providers
- **Provider timeouts**: Providers may retry if responses take too long
- **Resource contention**: Long-running operations block webhook acceptance
- **Failure propagation**: Processing failures can cause webhook acceptance to fail

EventRelay uses **asynchronous processing** to:
- ✅ Return HTTP 200 immediately upon event acceptance
- ✅ Decouple webhook acceptance from business logic execution
- ✅ Handle traffic spikes gracefully
- ✅ Isolate processing failures from webhook acceptance

**Implementation**: Events are persisted in `RECEIVED` state immediately. A background worker (`WebhookEventWorker`) polls for eligible events and processes them asynchronously.

### 2. Idempotency Guarantee

**How idempotency is guaranteed:**

1. **Database-Level Uniqueness Constraint**
   ```sql
   UNIQUE (source, external_event_id)
   ```
   - Prevents duplicate events from being stored
   - Enforced at the database level for absolute guarantee

2. **Idempotent Acceptance**
   - When a duplicate event arrives, the database throws `DataIntegrityViolationException`
   - The controller catches this exception and returns HTTP 200 OK
   - The duplicate event is acknowledged but not stored again

3. **State-Based Processing Guard**
   - Events can only be processed if in `RECEIVED` or `FAILED` states
   - Once an event reaches `SUCCESS`, it's not eligible for processing again
   - Database row locking (`FOR UPDATE SKIP LOCKED`) prevents concurrent processing

4. **Transaction Safety**
   - State transitions are atomic (wrapped in `@Transactional`)
   - Processing claims events atomically, preventing race conditions

**Example Flow:**
```
1. Provider sends webhook with external_event_id="evt_123"
2. EventRelay stores event → RECEIVED state
3. Provider retries (same external_event_id)
4. Database constraint violation → HTTP 200 OK (idempotent)
5. Worker processes original event → SUCCESS
6. Future retries are ignored for storage (already stored)
```

**Note on "exactly-once"**: The system guarantees exactly-once *ingestion* (deduplication) via a database constraint. Processing semantics depend on your business logic handler; handlers should be implemented idempotently to prevent duplicate side effects in the presence of failures.

### 3. Retry and Failure Handling

**Retry Strategy:**

EventRelay implements **exponential backoff** with the following schedule:

| Retry Attempt | Delay |
|--------------|-------|
| 1             | 30 seconds |
| 2             | 2 minutes |
| 3             | 10 minutes |
| 4             | 30 minutes |
| 5             | 2 hours |

**Failure Handling:**

1. **Transient Failures** (retry_count < max_retries)
   - Event transitions to `FAILED` state
   - `next_retry_at` is set based on exponential backoff
   - Event becomes eligible for retry after the delay

2. **Permanent Failures** (retry_count >= max_retries)
   - Event transitions to `DEAD_LETTER` state
   - Requires manual inspection and intervention
   - `next_retry_at` is cleared (no automatic retries)

3. **Worker Crashes**
   - Database row locks are scoped to the transaction; if a worker crashes, locks are released
   - An event may remain in `PROCESSING` state until an operator intervenes
   - Recovery (e.g., resetting stuck events back to `RECEIVED`) is currently a manual/operational action (e.g., via direct DB update)

**State Machine:**

```
RECEIVED → PROCESSING → SUCCESS
              ↓
           FAILED → (retry) → PROCESSING → SUCCESS
              ↓ (max retries exceeded)
         DEAD_LETTER
```

## Getting Started

### Prerequisites

- Java 21+
- Maven 3.6+
- PostgreSQL 12+

### Installation

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd EventRelay
   ```

2. **Set up PostgreSQL database**
   ```sql
   CREATE DATABASE event_relay;
   ```

3. **Configure application properties**
   
   Edit `src/main/resources/application.properties` or set environment variables:
   ```properties
   spring.datasource.url=jdbc:postgresql://localhost:5432/event_relay
   spring.datasource.username=postgres
   spring.datasource.password=postgres
   
   # Webhook secrets (one per source)
   eventrelay.webhook.secrets.test=test-secret
   eventrelay.webhook.secrets.paypal=your-paypal-secret
   eventrelay.webhook.secrets.zoom=your-zoom-secret
   ```

4. **Run database migrations**
   
   Flyway will automatically run migrations on startup. The initial migration creates the `webhook_events` table.

5. **Build and run**
   ```bash
   mvn clean install
   mvn spring-boot:run
   ```

The application will start on `http://localhost:8080` (default Spring Boot port).

## API Documentation

### Webhook Ingestion

#### POST /webhooks/{source}

Accepts webhook payloads from third-party providers.

**Path Parameters:**
- `source` (string, required): Identifier for the webhook source (e.g., "paypal", "zoom", "stripe")

**Headers:**
- `X-Webhook-Signature` (string, required): Base64-encoded HMAC-SHA256 signature of the request body
- `Content-Type: application/json` (required)

**Request Body:**
```json
{
  "id": "evt_1234567890",
  "type": "payment.completed",
  "data": { ... }
}
```

**Response:**
- `200 OK`: Webhook accepted (idempotent for duplicates)
- `400 Bad Request`: Missing or invalid event ID
- `401 Unauthorized`: Invalid signature

**Example:**
```bash
curl -X POST http://localhost:8080/webhooks/test \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: <base64-hmac-signature>" \
  -d '{"id":"evt_123","type":"test.event"}'
```

**Signature Generation:**
```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
byte[] signature = mac.doFinal(requestBody.getBytes());
String base64Signature = Base64.getEncoder().encodeToString(signature);
```

### Event Query APIs

#### GET /events/{eventId}

Retrieves a specific event by its UUID.

**Path Parameters:**
- `eventId` (UUID, required): The event's unique identifier

**Response:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "source": "test",
  "externalEventId": "evt_123",
  "state": "SUCCESS",
  "retryCount": 0,
  "nextRetryAt": null,
  "failureReason": null,
  "createdAt": "2025-01-01T12:00:00Z",
  "updatedAt": "2025-01-01T12:00:05Z"
}
```

**Status Codes:**
- `200 OK`: Event found
- `400 Bad Request`: Invalid event ID format
- `404 Not Found`: Event not found

#### GET /events

Queries events with optional filters.

**Query Parameters:**
- `state` (string, optional): Filter by state (`RECEIVED`, `PROCESSING`, `SUCCESS`, `FAILED`, `DEAD_LETTER`)
- `source` (string, optional): Filter by source identifier

**Response:**
```json
[
  {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "source": "test",
    "externalEventId": "evt_123",
    "state": "SUCCESS",
    ...
  },
  ...
]
```

**Examples:**
```bash
# Get all failed events
GET /events?state=FAILED

# Get all events from a specific source
GET /events?source=paypal

# Get failed events from a specific source
GET /events?state=FAILED&source=paypal
```

## How It Works

### Event Lifecycle

1. **Ingestion** (`RECEIVED`)
   - Webhook arrives at `POST /webhooks/{source}`
   - Signature is verified using HMAC-SHA256
   - Event ID is extracted from payload (`id` field)
   - Event is persisted with `RECEIVED` state
   - HTTP 200 is returned immediately

2. **Processing** (`PROCESSING`)
   - Worker polls for eligible events (`RECEIVED` or `FAILED` with `next_retry_at <= now()`)
   - Event is claimed using `FOR UPDATE SKIP LOCKED` (prevents concurrent processing)
   - State transitions to `PROCESSING`
   - Business logic handler is invoked

3. **Success** (`SUCCESS`)
   - Handler completes successfully
   - State transitions to `SUCCESS`
   - Event is never processed again

4. **Failure** (`FAILED`)
   - Handler throws an exception
   - State transitions to `FAILED`
   - `retry_count` is incremented
   - `next_retry_at` is set based on exponential backoff
   - Event becomes eligible for retry after the delay

5. **Dead Letter** (`DEAD_LETTER`)
   - After max retries exceeded, event transitions to `DEAD_LETTER`
   - Requires manual intervention
   - No automatic retries

### Concurrency Safety

EventRelay uses **database row locking** to ensure safe concurrent processing:

```sql
SELECT * FROM webhook_events
WHERE state IN ('RECEIVED', 'FAILED')
  AND (next_retry_at IS NULL OR next_retry_at <= NOW())
ORDER BY created_at ASC
LIMIT 1
FOR UPDATE SKIP LOCKED
```

- `FOR UPDATE`: Locks the row for the transaction
- `SKIP LOCKED`: Skips already-locked rows, allowing parallel workers
- Multiple workers can process different events concurrently
- No race conditions or duplicate processing

## Configuration

### Application Properties

| Property | Default | Description |
|----------|---------|-------------|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/event_relay` | PostgreSQL connection URL |
| `spring.datasource.username` | `postgres` | Database username |
| `spring.datasource.password` | `postgres` | Database password |
| `eventrelay.webhook.secrets.{source}` | - | HMAC secret for each webhook source |
| `eventrelay.retry.max` | `5` | Maximum number of retry attempts |
| `eventrelay.worker.batchSize` | `5` | Number of events to process per worker tick |
| `eventrelay.worker.fixedDelayMs` | `1000` | Delay between worker polling cycles (milliseconds) |

### Environment Variables

Database configuration is wired for environment variable overrides by default. Webhook secrets can be set via properties (e.g., `eventrelay.webhook.secrets.paypal=...`); the repo currently includes an environment-variable example for the `test` source.

```bash
export EVENT_RELAY_DB_URL=jdbc:postgresql://localhost:5432/event_relay
export EVENT_RELAY_DB_USERNAME=postgres
export EVENT_RELAY_DB_PASSWORD=postgres
export EVENT_RELAY_WEBHOOK_SECRET_TEST=test-secret
```

### Custom Business Logic Handler

Implement the `WebhookEventHandler` interface:

```java
@Component
public class CustomWebhookEventHandler implements WebhookEventHandler {
    @Override
    public void handle(WebhookEvent event) {
        // Your business logic here
        // Throw RuntimeException to trigger retry
    }
}
```

The default handler (`DefaultWebhookEventHandler`) logs events and can simulate failures for testing.

## Testing

### Running Tests

```bash
# Run all tests
mvn test

# Run specific test class
mvn test -Dtest=WebhookFlowIntegrationTests
```

### Test Coverage

The project includes comprehensive integration tests using Testcontainers:

- **Idempotency Tests**: Verify duplicate webhooks are handled correctly
- **Retry Flow Tests**: Verify exponential backoff and retry logic
- **Dead Letter Tests**: Verify events move to DEAD_LETTER after max retries
- **Signature Verification Tests**: Verify HMAC signature validation

### Test Database

Tests use Testcontainers to spin up a PostgreSQL container automatically. No manual database setup required for testing.

## Known Limitations & Tradeoffs

### 1. Single Database as Bottleneck

**Tradeoff**: PostgreSQL serves as both the event store and the processing queue.

**Impact:**
- Database becomes a bottleneck under very high load
- Row locking can cause contention with many concurrent workers

**Mitigation:**
- Use connection pooling
- Optimize database indexes
- Consider read replicas for query APIs

### 2. Polling-Based Processing

**Tradeoff**: Workers poll the database instead of using push-based messaging.

**Impact:**
- Higher database load due to constant polling
- Slight processing delay (up to `fixedDelayMs`)

**Mitigation:**
- Adjustable polling interval
- Batch processing reduces query frequency
- Can be replaced with message queue in future

### 3. No Built-in UI Dashboard

**Tradeoff**: No graphical interface for monitoring events.

**Impact:**
- Requires API calls or database queries for monitoring
- Less user-friendly for non-technical users

**Mitigation:**
- Comprehensive REST APIs for querying events
- Structured logging for observability
- Can build custom dashboard using APIs

### 4. Manual Dead Letter Handling

**Tradeoff**: DEAD_LETTER events require manual intervention.

**Impact:**
- No automatic recovery for permanently failed events
- Requires operational overhead

**Mitigation:**
- Clear failure reasons logged
- Query APIs enable easy identification of dead letters
- Can implement admin APIs for manual retry

### 5. Transaction Rollback on Processing Failure

**Tradeoff**: Processing failures roll back the entire transaction.

**Impact:**
- State transition to FAILED happens after rollback
- Requires careful transaction management

**Mitigation:**
- State transitions are atomic
- Failure handling is explicit and logged

## Scaling Considerations

### Horizontal Scaling

**Current State**: Single instance deployment

**Scaling Path:**

1. **Multiple Worker Instances**
   - Deploy multiple application instances
   - Each instance runs the `WebhookEventWorker`
   - Database row locking (`SKIP LOCKED`) enables safe concurrent processing
   - No coordination needed between instances

2. **Database Optimization**
   - Add read replicas for query APIs (`EventsController`)
   - Use connection pooling (HikariCP)
   - Optimize indexes for common query patterns
   - Consider partitioning by `source` or date

3. **Worker Separation** (Future)
   - Separate ingestion API from worker processes
   - Scale workers independently based on processing load
   - Use message queue (RabbitMQ, Kafka) instead of database polling

4. **Caching Layer** (Future)
   - Cache frequently accessed events
   - Reduce database load for query APIs
   - Use Redis for distributed caching

### Performance Tuning

**Database:**
- Increase `max_connections` in PostgreSQL
- Tune `shared_buffers` and `work_mem`
- Monitor slow queries and optimize

**Application:**
- Adjust `eventrelay.worker.batchSize` based on processing time
- Tune `eventrelay.worker.fixedDelayMs` for latency vs. load tradeoff
- Use connection pool sizing appropriate for worker count

**Monitoring:**
- Track event processing latency
- Monitor database connection pool usage
- Alert on DEAD_LETTER events
- Track retry rates and failure patterns

### Migration to Message Queue

For very high throughput, consider migrating from database polling to a message queue:

1. **Ingestion**: Continue storing events in PostgreSQL
2. **Queue**: Publish events to RabbitMQ/Kafka after ingestion
3. **Workers**: Consume from queue instead of polling database
4. **State Updates**: Update PostgreSQL state after processing

This maintains durability while improving throughput.

## Project Structure

```
EventRelay/
├── src/
│   ├── main/
│   │   ├── java/com/ansh/EventRelay/
│   │   │   ├── EventRelayApplication.java      # Main application class
│   │   │   ├── events/                         # Event domain
│   │   │   │   ├── WebhookEvent.java          # Entity
│   │   │   │   ├── WebhookEventState.java     # State enum
│   │   │   │   ├── WebhookEventRepository.java # Repository
│   │   │   │   ├── WebhookEventDto.java       # DTO
│   │   │   │   └── EventsController.java       # Query API
│   │   │   ├── webhooks/                       # Webhook ingestion
│   │   │   │   ├── WebhookIngestionController.java # Ingestion API
│   │   │   │   ├── WebhookIngestionService.java    # Business logic
│   │   │   │   ├── SignatureVerifier.java          # HMAC verification
│   │   │   │   ├── WebhookSecretsProperties.java   # Configuration
│   │   │   │   └── ApiExceptionHandler.java         # Error handling
│   │   │   └── worker/                         # Async processing
│   │   │       ├── WebhookEventWorker.java     # Scheduled worker
│   │   │       ├── WebhookEventProcessor.java  # Processing logic
│   │   │       ├── WebhookEventHandler.java   # Handler interface
│   │   │       ├── DefaultWebhookEventHandler.java # Default impl
│   │   │       └── RetryPolicy.java            # Retry strategy
│   │   └── resources/
│   │       ├── application.properties          # Configuration
│   │       └── db/migration/                   # Flyway migrations
│   │           └── V1__create_webhook_events.sql
│   └── test/                                    # Test classes
├── pom.xml                                     # Maven configuration
├── PRD.md                                      # Product requirements
└── README.md                                   # This file
```
## Author

Anshpreet Singh

---

**Note**: This project prioritizes correctness and debuggability over raw throughput. It's designed for production use cases where reliability and observability are more important than maximum performance.

