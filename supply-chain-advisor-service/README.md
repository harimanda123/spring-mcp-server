# Supply Chain Advisor Service

Ingests shipment data, monitors global disaster feeds (GDACS), and autonomously generates actionable risk advisories — all without blocking the ERP that pushes data to it.

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Architecture Overview](#2-architecture-overview)
3. [Component Breakdown](#3-component-breakdown)
4. [Data Model](#4-data-model)
5. [REST API Reference](#5-rest-api-reference)
6. [Configuration Reference](#6-configuration-reference)
7. [How to Build and Run](#7-how-to-build-and-run)
8. [End-to-End Test Walkthrough](#8-end-to-end-test-walkthrough)
9. [Example JSON Payloads](#9-example-json-payloads)
10. [Tech Stack](#10-tech-stack)

---

## 1. Problem Statement

Enterprise supply chains face two compounding problems:

| Problem | Consequence |
|---|---|
| ERP systems push bulk shipment updates synchronously and wait for a response | Any slow intelligence logic blocks the ERP, causing timeouts and data backlogs |
| Disaster events (earthquakes, floods, hurricanes) are discovered manually | Shipments routed through affected zones are only identified after delays occur |

**This service solves both:**

- The ERP never waits — every write returns immediately while analysis runs on a dedicated async thread pool.
- A scheduled feed reader polls [GDACS](https://www.gdacs.org) every hour, and the moment a qualifying disaster event is detected, every `IN_TRANSIT` shipment at the affected location is re-evaluated and assigned a risk severity in real time.

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                          ERP / Client                           │
└────────────┬──────────────────────┬──────────────────┬──────────┘
             │ POST /batch          │ POST /events      │ GET *
             ▼                      ▼                   ▼
┌────────────────────────────────────────────────────────────────┐
│                     REST Controllers (HTTP)                     │
│   ShipmentController  │  GlobalEventController  │ AdviceController│
└────────┬──────────────┴──────────────┬───────────┴─────────────┘
         │ (sync, returns 200)          │ (sync, returns 201)
         ▼                              ▼
┌─────────────────┐          ┌──────────────────────┐
│ ShipmentService │          │  GlobalEventService   │
│  (upsert, ACID) │          │  (upsert / soft-del)  │
└────────┬────────┘          └──────────┬────────────┘
         │ fire-and-forget               │ fire-and-forget
         ▼                              ▼
┌────────────────────────────────────────────────────────────────┐
│               intelligenceExecutor Thread Pool                  │
│              (core=4, max=10, queue=200, CallerRuns)            │
│                                                                  │
│  AdviceService.processIntelligence()                            │
│  AdviceService.reEvaluateImpactedShipments()    ← ERP event     │
│  AdviceService.evaluateInTransitShipmentsForEvent() ← GDACS     │
└────────┬───────────────────────────────────────────────────────┘
         │ paginated DB reads (BATCH_SIZE=500)
         ▼
┌─────────────────────────────────────────────────────────────────┐
│                     H2 / Any RDBMS                              │
│   shipments  │  shipment_advice  │  global_events               │
└─────────────────────────────────────────────────────────────────┘
         ▲
┌────────┴──────────────────────────────────────────┐
│         GlobalIntelligenceService (Scheduled)      │
│  @Scheduled(cron="0 0 * * * *")                   │
│  → fetches https://www.gdacs.org/xml/rss.xml       │
│  → filters by <gdacs:alertlevel> (RED / ORANGE)    │
│  → upserts GlobalEvent                             │
│  → triggers AdviceService.evaluate...()            │
└───────────────────────────────────────────────────┘
         ▲
┌────────┴──────────────────────────────────────────┐
│              RecoveryTask (Scheduled)              │
│  @Scheduled(fixedDelay=60_000)                    │
│  → finds PENDING shipments > 2 min old            │
│  → re-queues them (JVM crash recovery)            │
└───────────────────────────────────────────────────┘
```

### Key Design Decisions

| Decision | Rationale |
|---|---|
| **Sync-Async Handshake** | Controllers commit the write transactionally, return HTTP 200/201, then fire async intelligence. ERP is never blocked. |
| **Dedicated thread pool** | `intelligenceExecutor` (4–10 threads, 200-item queue, CallerRunsPolicy) isolates analysis from the web thread pool. |
| **Paginated DB reads** | All evaluation loops read in pages of 500 rows — heap pressure is constant regardless of dataset size. |
| **DB-level status filter** | `findShipmentsByLocationAndStatus` applies `WHERE status = 'IN_TRANSIT'` in SQL, never in Java, preventing full-table materialisation. |
| **GDACS byte[] fetch** | RSS feed is fetched as `byte[]`, BOM-stripped, and parsed via `ByteArrayInputStream` — prevents `SAXParseException: Content is not allowed in prolog`. |
| **XXE prevention** | `DocumentBuilderFactory` disables DOCTYPE declarations and all external entity resolution (OWASP A05). |
| **Daily idempotency guard** | `ShipmentAdvice` records include a same-day duplicate check — pushing the same event twice never floods the ERP with duplicate advisories. |
| **Recovery Janitor** | Shipments stuck in `PENDING` for > 2 minutes are automatically re-queued, recovering from mid-analysis JVM crashes. |

---

## 3. Component Breakdown

### Controllers

| Class | Base Path | Role |
|---|---|---|
| `ShipmentController` | `/api/v1/shipments` | CRUD + batch ingest; triggers async intelligence |
| `AdviceController` | `/api/v1/shipments/advice` | ERP polling interface for generated advisories |
| `GlobalEventController` | `/api/v1/events` | ERP event push; manual GDACS trigger; admin |

### Services

| Class | Trigger | Responsibility |
|---|---|---|
| `ShipmentService` | HTTP (sync) | Transactional batch upsert; marks shipments `PENDING` |
| `AdviceService` | Async | Intelligence engine: internal rules + global event cross-reference + severity mapping |
| `GlobalEventService` | HTTP (sync) | Upsert / soft-delete `GlobalEvent` records pushed by ERP |
| `GlobalIntelligenceService` | `@Scheduled` hourly | Fetches GDACS RSS, filters by configurable alert levels, upserts events, triggers evaluation |
| `RecoveryTask` | `@Scheduled` every 60 s | Finds stale `PENDING` shipments and re-queues them |

### Intelligence Rules (inside `AdviceService`)

| Rule | Condition | Advice Type | Severity |
|---|---|---|---|
| Delay Risk | `priority=HIGH` AND `status=DELAYED` | `DELAY_RISK` | `CRITICAL` |
| Overdue | `estimatedDelivery` in past AND not `DELIVERED` | `OVERDUE` | `WARNING` |
| Global Event — RED | Shipment location overlaps active RED alert | `GLOBAL_EVENT_*` | `CRITICAL` |
| Global Event — ORANGE | Shipment location overlaps active ORANGE alert | `GLOBAL_EVENT_*` | `HIGH` |
| Global Event — GREEN | Shipment location overlaps active GREEN alert | `GLOBAL_EVENT_*` | `LOW` |

---

## 4. Data Model

### `shipments`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | Auto-generated |
| `tracking_number` | VARCHAR UNIQUE | Business key |
| `sku` | VARCHAR | Product identifier |
| `quantity` | INTEGER | |
| `status` | VARCHAR | e.g. `IN_TRANSIT`, `DELAYED`, `DELIVERED` |
| `origin_hub` | VARCHAR | |
| `destination_hub` | VARCHAR | |
| `current_location` | VARCHAR | |
| `carrier_code` | VARCHAR | |
| `priority` | VARCHAR | `HIGH`, `MEDIUM`, `LOW` |
| `estimated_delivery` | DATE | |
| `updated_at` | TIMESTAMP | Auto-managed by Hibernate |
| `processing_status` | ENUM | `PENDING` → `COMPLETED` / `FAILED` |
| `last_processed_at` | TIMESTAMP | Set after each intelligence run |
| `issue_type` | VARCHAR | e.g. `GLOBAL_EVENT_EARTHQUAKE` |
| `advice_message` | VARCHAR(1024) | Human-readable risk description |
| `severity` | VARCHAR | `CRITICAL`, `HIGH`, `LOW`, `UNKNOWN` |
| `recommended_action` | VARCHAR(512) | Operator instruction |

### `shipment_advice`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `tracking_number` | VARCHAR | Foreign reference (not FK) |
| `issue_type` | VARCHAR | |
| `severity` | VARCHAR | |
| `advice_message` | VARCHAR(1024) | |
| `recommended_action` | VARCHAR(1024) | |
| `status` | VARCHAR | `PENDING` → `POLLED` |
| `created_at` | TIMESTAMP | Auto-managed |

### `global_events`

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | |
| `location` | VARCHAR | Country / city name |
| `event_type` | VARCHAR | e.g. `Earthquake`, `Flood` |
| `severity` | VARCHAR | `RED`, `ORANGE`, `GREEN` |
| `is_active` | BOOLEAN | `false` = soft-deleted |
| `created_at` | TIMESTAMP | |
| `updated_at` | TIMESTAMP | |

---

## 5. REST API Reference

### Shipments

#### `POST /api/v1/shipments/batch` — Batch Upsert
Persists one or more shipments (insert or update by `trackingNumber`) and immediately returns. Intelligence analysis runs asynchronously.

**Request body:** `application/json` — array of Shipment objects  
**Response:** `200 OK` — array of persisted Shipment objects

---

#### `GET /api/v1/shipments/{trackingNumber}` — Get by Tracking Number
**Response:** `200 OK` — single Shipment · `404 Not Found`

---

#### `GET /api/v1/shipments/severity/{severity}` — Get by Severity
Returns all shipments whose intelligence-engine `severity` field matches the path value (case-insensitive).

| Path value | Returns |
|---|---|
| `CRITICAL` | RED-alert impacted shipments |
| `HIGH` | ORANGE-alert impacted shipments |
| `LOW` | GREEN-alert / low-risk shipments |
| `UNKNOWN` | Events with unrecognised alert level |

**Response:** `200 OK` — array (empty list if no matches)

---

#### `GET /api/v1/shipments/location/{location}` — Get by Location
Returns all shipments whose `currentLocation` **or** `destinationHub` matches the path value (case-insensitive). Useful for operator triage by hub.

**Response:** `200 OK` — array (empty list if no matches)

---

### Advice

#### `GET /api/v1/shipments/advice` — Poll Pending Advice
Returns all `ShipmentAdvice` records with `status = PENDING` and atomically marks them `POLLED` in a single transaction. Safe for repeated polling — records are never returned twice.

**Response:** `200 OK` — array of ShipmentAdvice objects

---

### Global Events

#### `GET /api/v1/events` — List All Events
Returns all `GlobalEvent` records including inactive ones.

**Response:** `200 OK`

---

#### `POST /api/v1/events` — Push ERP Events
Upserts one or more `GlobalEvent` records (ERP data takes precedence over GDACS). After each upsert, triggers `reEvaluateImpactedShipments` asynchronously for shipments at the event's location.

**Request body:** `application/json` — array of GlobalEvent objects  
**Response:** `201 Created` — array of saved GlobalEvent objects

---

#### `DELETE /api/v1/events/{id}` — Deactivate Event
Soft-deletes a `GlobalEvent` by setting `isActive = false`. Does not remove the row.

**Response:** `204 No Content` · `404 Not Found`

---

#### `GET /api/v1/events/trigger-fetch` — Manual GDACS Fetch
Manually triggers the GDACS RSS poll outside the hourly schedule. Useful for demos and immediate testing.

**Response:** `200 OK` — `"Public risk fetch initiated and re-evaluation triggered."`

---

## 6. Configuration Reference

All settings live in `src/main/resources/application.properties`.

| Property | Default | Description |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `spring.datasource.url` | H2 in-memory | Replace with PostgreSQL / MySQL URL for production |
| `spring.h2.console.enabled` | `true` | Disable in production profiles |
| `spring.jpa.hibernate.ddl-auto` | `update` | Use `validate` or `none` in production |
| `gdacs.alert.severities` | `RED,ORANGE` | Comma-separated GDACS `<gdacs:alertlevel>` values to act on. Must be UPPERCASE, no spaces. Add `GREEN` to expand coverage. |

**To add `GREEN` alert monitoring:**
```properties
gdacs.alert.severities=RED,ORANGE,GREEN
```

**To switch to PostgreSQL:**
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/supplychain
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.username=your_user
spring.datasource.password=your_password
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.h2.console.enabled=false
```

---

## 7. How to Build and Run

### Prerequisites

- Java 21+
- Maven 3.9+

### Run

```bash
cd c:\WorkSpace\supply-chain-advisor-service
mvn spring-boot:run
```

### Build JAR

```bash
mvn clean package -DskipTests
java -jar target/supply-chain-advisor-service-0.0.1-SNAPSHOT.jar
```

### Useful URLs (after startup)

| URL | Purpose |
|---|---|
| `http://localhost:8080/swagger-ui.html` | Interactive Swagger UI for all endpoints |
| `http://localhost:8080/h2-console` | H2 database browser (JDBC URL: `jdbc:h2:mem:supplychain`) |
| `http://localhost:8080/api/v1/events/trigger-fetch` | Manually trigger GDACS feed |

---

## 8. End-to-End Test Walkthrough

### Scenario: Shipment at a disaster-affected hub gets re-evaluated

**Step 1 — Ingest a shipment**

```bash
curl -s -X POST http://localhost:8080/api/v1/shipments/batch \
  -H "Content-Type: application/json" \
  -d '[
    {
      "trackingNumber": "TRK-DEMO-001",
      "sku": "ELEC-GPU-4090",
      "quantity": 50,
      "status": "IN_TRANSIT",
      "originHub": "Shenzhen",
      "destinationHub": "Singapore",
      "currentLocation": "Singapore",
      "carrierCode": "DHL",
      "priority": "HIGH",
      "estimatedDelivery": "2026-05-10"
    }
  ]'
```

Immediately returns `200 OK`. Intelligence runs in background.

---

**Step 2 — Push a simulated ERP disaster event at the same location**

```bash
curl -s -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -d '[
    {
      "location": "Singapore",
      "eventType": "Flood",
      "severity": "RED",
      "isActive": true
    }
  ]'
```

Returns `201 Created`. Asynchronously triggers re-evaluation of all shipments at Singapore.

---

**Step 3 — Wait ~1 second, then check the shipment**

```bash
curl -s http://localhost:8080/api/v1/shipments/TRK-DEMO-001 | python -m json.tool
```

Expected intelligence fields in the response:

```json
{
  "trackingNumber": "TRK-DEMO-001",
  "severity": "CRITICAL",
  "issueType": "GLOBAL_EVENT_FLOOD",
  "adviceMessage": "Active RED alert: Flood detected near shipment location 'Singapore'.",
  "recommendedAction": "Immediate Reroute Required.",
  "processingStatus": "COMPLETED"
}
```

---

**Step 4 — Poll the advice queue**

```bash
curl -s http://localhost:8080/api/v1/shipments/advice | python -m json.tool
```

Returns all `PENDING` advice records and marks them `POLLED` atomically.

---

**Step 5 — Query by severity**

```bash
curl -s http://localhost:8080/api/v1/shipments/severity/CRITICAL | python -m json.tool
```

---

**Step 6 — Query by location**

```bash
curl -s "http://localhost:8080/api/v1/shipments/location/Singapore" | python -m json.tool
```

Returns all shipments currently at Singapore or destined for Singapore.

---

**Step 7 — Trigger the GDACS RSS fetch manually**

```bash
curl -s http://localhost:8080/api/v1/events/trigger-fetch
```

Fetches live disaster data from `https://www.gdacs.org/xml/rss.xml`, filters for RED and ORANGE alerts, and re-evaluates any matching in-transit shipments.

---

**Step 8 — Deactivate an event**

```bash
curl -s -X DELETE http://localhost:8080/api/v1/events/1
```

Returns `204 No Content`. Sets `isActive = false` — the row is preserved for audit.

---

## 9. Example JSON Payloads

### POST `/api/v1/shipments/batch` — multiple shipments

```json
[
  {
    "trackingNumber": "TRK-2026-001",
    "sku": "MED-VIALS-X100",
    "quantity": 200,
    "status": "IN_TRANSIT",
    "originHub": "Frankfurt",
    "destinationHub": "Mumbai",
    "currentLocation": "Dubai",
    "carrierCode": "FedEx",
    "priority": "HIGH",
    "estimatedDelivery": "2026-05-08"
  },
  {
    "trackingNumber": "TRK-2026-002",
    "sku": "AUTO-PARTS-BR",
    "quantity": 5000,
    "status": "DELAYED",
    "originHub": "Busan",
    "destinationHub": "Rotterdam",
    "currentLocation": "Strait of Malacca",
    "carrierCode": "Maersk",
    "priority": "HIGH",
    "estimatedDelivery": "2026-05-01"
  },
  {
    "trackingNumber": "TRK-2026-003",
    "sku": "FMCG-RICE-50KG",
    "quantity": 10000,
    "status": "IN_TRANSIT",
    "originHub": "Bangkok",
    "destinationHub": "Nairobi",
    "currentLocation": "Colombo",
    "carrierCode": "MSC",
    "priority": "MEDIUM",
    "estimatedDelivery": "2026-05-20"
  }
]
```

**What this triggers:**
- `TRK-2026-001`: `estimatedDelivery` is past → `OVERDUE / WARNING` advice generated
- `TRK-2026-002`: `HIGH` priority + `DELAYED` → `DELAY_RISK / CRITICAL` advice; also `OVERDUE` since delivery date passed
- `TRK-2026-003`: No rules triggered immediately; eligible for GDACS cross-reference

---

### POST `/api/v1/events` — ERP global events

```json
[
  {
    "location": "Dubai",
    "eventType": "Earthquake",
    "severity": "RED",
    "isActive": true
  },
  {
    "location": "Colombo",
    "eventType": "Cyclone",
    "severity": "ORANGE",
    "isActive": true
  }
]
```

**What this triggers:**
- Dubai is `TRK-2026-001`'s `currentLocation` → severity mapped to `CRITICAL`, recommended action: "Immediate Reroute Required."
- Colombo is `TRK-2026-003`'s `currentLocation` → severity mapped to `HIGH`, recommended action: "Plan alternative routing."

---

### Shipment after full re-evaluation — GET response

```json
{
  "id": 1,
  "trackingNumber": "TRK-2026-001",
  "sku": "MED-VIALS-X100",
  "quantity": 200,
  "status": "IN_TRANSIT",
  "originHub": "Frankfurt",
  "destinationHub": "Mumbai",
  "currentLocation": "Dubai",
  "carrierCode": "FedEx",
  "priority": "HIGH",
  "estimatedDelivery": "2026-05-08",
  "processingStatus": "COMPLETED",
  "lastProcessedAt": "2026-05-14T10:35:22.841",
  "issueType": "GLOBAL_EVENT_EARTHQUAKE",
  "adviceMessage": "Active RED alert: Earthquake detected near shipment location 'Dubai'.",
  "severity": "CRITICAL",
  "recommendedAction": "Immediate Reroute Required."
}
```

---

### GET `/api/v1/shipments/advice` — advice poll response

```json
[
  {
    "id": 1,
    "trackingNumber": "TRK-2026-001",
    "issueType": "OVERDUE",
    "severity": "WARNING",
    "adviceMessage": "Shipment TRK-2026-001 has passed its estimated delivery date and has not been delivered.",
    "recommendedAction": "Initiate delivery-exception process. Notify the customer and escalate to the logistics manager for resolution.",
    "status": "POLLED",
    "createdAt": "2026-05-14T10:35:20.123"
  },
  {
    "id": 2,
    "trackingNumber": "TRK-2026-002",
    "issueType": "DELAY_RISK",
    "severity": "CRITICAL",
    "adviceMessage": "High priority shipment is delayed. Immediate action required for SKU AUTO-PARTS-BR.",
    "recommendedAction": "Contact carrier immediately and arrange express re-routing. Notify the receiving warehouse of the revised ETA.",
    "status": "POLLED",
    "createdAt": "2026-05-14T10:35:20.456"
  }
]
```

---

## 10. Tech Stack

| Technology | Version | Purpose |
|---|---|---|
| Java | 21 (LTS) | Language; switch expressions, pattern matching |
| Spring Boot | 3.3.5 | Application framework |
| Spring Data JPA | (managed) | Repository layer, JPQL queries |
| Spring Web | (managed) | REST controllers |
| Spring Async / Scheduling | (managed) | `@Async`, `@Scheduled`, thread pool config |
| H2 Database | (managed) | In-memory RDBMS; swap for any RDBMS |
| Lombok | 1.18.46 | Boilerplate reduction (`@Builder`, `@Slf4j`, etc.) |
| SpringDoc OpenAPI | 2.6.0 | Auto-generated Swagger UI |
| Maven | 3.9+ | Build tool |
