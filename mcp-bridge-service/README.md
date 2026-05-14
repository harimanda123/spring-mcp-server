# mcp-bridge-service

A production-ready **MCP (Model Context Protocol) Server** built with Spring Boot 3.3.5 and Java 21. It bridges AI clients to the [supply-chain-advisor-service](../supply-chain-advisor-service) REST API, exposing shipment data as MCP tools with full security, audit logging, circuit-breaking, caching, and monitoring.

---

## Table of Contents

- [Architecture](#architecture)
- [MCP Tools](#mcp-tools)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [Running Locally](#running-locally)
- [Running with Docker](#running-with-docker)
- [Security](#security)
- [Monitoring & Health](#monitoring--health)
- [Testing](#testing)

---

## Architecture

```
MCP Client (AI Agent)
        │
        │  SSE transport  (GET /mcp/sse)
        │  Messages       (POST /mcp/message)
        ▼
┌─────────────────────────────────────────────┐
│            mcp-bridge-service               │
│                                             │
│  RateLimitingFilter  → ApiKeyAuthFilter     │
│  AuditLoggingAspect  (AOP, all tools)       │
│                                             │
│  ShipmentTools                              │
│    getShipmentByTrackingNumber              │
│    getShipmentsBySeverity    [cached 5 min] │
│    getShipmentsByLocation                   │
│                                             │
│  ShipmentApiClient   [circuit breaker]      │
└────────────────┬────────────────────────────┘
                 │  HTTP / REST
                 ▼
    supply-chain-advisor-service
      GET /api/v1/shipments/{trackingNumber}
      GET /api/v1/shipments/severity/{severity}
      GET /api/v1/shipments/location/{location}
```

### Production standards implemented

| Standard | Implementation |
|---|---|
| API key authentication | `ApiKeyAuthFilter` — `X-API-Key` request header |
| Role-based access control | `READ` / `WRITE` roles; `@PreAuthorize` per tool |
| Rate limiting | `RateLimitingFilter` — Bucket4j token bucket, 60 req/min per key |
| Audit logging | `AuditLoggingAspect` — AOP, every tool call logged with outcome and duration |
| Circuit breaker | Resilience4j `@CircuitBreaker` on all REST client methods |
| Caching | Caffeine — `getShipmentsBySeverity` cached for 5 minutes |
| Health checks | Spring Actuator + `SupplyChainAdvisorHealthIndicator` |
| Metrics | Micrometer + Prometheus scrape endpoint |
| Error handling | `ShipmentServiceUnavailableException` — no internal detail leakage |
| Containerisation | Multi-stage Dockerfile, non-root user, container-aware JVM flags |

---

## MCP Tools

All three tools require **READ role**. Because a role hierarchy (`WRITE > READ`) is configured, WRITE key holders automatically satisfy the READ requirement.

| Tool | REST Endpoint Bridged | Min Role | Description |
|---|---|---|---|
| `getShipmentByTrackingNumber` | `GET /api/v1/shipments/{trackingNumber}` | READ | Fetch a single shipment by its unique tracking number |
| `getShipmentsBySeverity` | `GET /api/v1/shipments/severity/{severity}` | READ | List all shipments matching a severity level (CRITICAL / HIGH / LOW / UNKNOWN) |
| `getShipmentsByLocation` | `GET /api/v1/shipments/location/{location}` | READ | List all shipments whose current location or destination hub matches the given name |

---

## Project Structure

```
mcp-bridge-service/
├── Dockerfile
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/example/mcpbridge/
    │   │   ├── McpBridgeApplication.java          # Entry point
    │   │   ├── audit/
    │   │   │   └── AuditLoggingAspect.java         # AOP audit trail for every tool call
    │   │   ├── client/
    │   │   │   └── ShipmentApiClient.java          # REST client with circuit breaker
    │   │   ├── config/
    │   │   │   ├── AppProperties.java              # Typed configuration properties
    │   │   │   ├── CacheConfig.java                # Caffeine cache manager
    │   │   │   ├── McpToolsConfig.java             # Registers tools with the MCP server
    │   │   │   ├── RestClientConfig.java           # RestClient + ObjectMapper beans
    │   │   │   └── SecurityConfig.java             # HTTP security, filter chain
    │   │   ├── health/
    │   │   │   └── SupplyChainAdvisorHealthIndicator.java
    │   │   ├── model/
    │   │   │   └── Shipment.java                   # DTO mirroring upstream entity
    │   │   ├── security/
    │   │   │   ├── ApiKeyAuthFilter.java           # Validates X-API-Key header
    │   │   │   └── RateLimitingFilter.java         # Bucket4j per-key rate limiter
    │   │   └── tools/
    │   │       └── ShipmentTools.java              # MCP tool definitions (@Tool methods)
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/com/example/mcpbridge/
            ├── security/
            │   └── ApiKeyAuthFilterTest.java
            └── tools/
                └── ShipmentToolsTest.java
```

---

## Prerequisites

| Requirement | Version |
|---|---|
| Java | 21+ |
| Maven | 3.9+ |
| supply-chain-advisor-service | running and reachable |

---

## Configuration

All runtime values are driven by environment variables. Defaults (shown below) are **for local development only** and must be overridden in any shared environment.

| Environment Variable | Default | Description |
|---|---|---|
| `SUPPLY_CHAIN_ADVISOR_URL` | `http://localhost:8080` | Base URL of the upstream service |
| `API_KEY_READ` | `dev-read-key-CHANGE-ME-in-production` | API key granting READ access |
| `API_KEY_WRITE` | `dev-write-key-CHANGE-ME-in-production` | API key granting WRITE access |

Additional tuning via `application.yml`:

```yaml
app:
  supply-chain-advisor:
    connect-timeout-ms: 5000    # HTTP connection timeout to upstream
    read-timeout-ms:    10000   # HTTP read timeout to upstream
  rate-limiting:
    requests-per-minute: 60     # Token-bucket limit per API key

resilience4j:
  circuitbreaker:
    instances:
      shipmentApi:
        slidingWindowSize:       10
        failureRateThreshold:    50   # % failures to open circuit
        waitDurationInOpenState: 30s
```

---

## Running Locally

```bash
# 1. Set environment variables (PowerShell)
$env:SUPPLY_CHAIN_ADVISOR_URL = "http://localhost:8080"
$env:API_KEY_READ              = "my-read-key"
$env:API_KEY_WRITE             = "my-write-key"

# 2. Build and run
mvn spring-boot:run
```

The MCP server starts on port **8081**.

| Endpoint | Purpose |
|---|---|
| `GET  /mcp/sse` | MCP SSE stream (MCP client connects here) |
| `POST /mcp/message` | MCP message endpoint |
| `GET  /actuator/health` | Health check (no auth required) |
| `GET  /actuator/prometheus` | Prometheus metrics (WRITE role required) |

### Calling a tool manually (curl example)

```bash
# Get shipment by tracking number
curl -H "X-API-Key: my-read-key" \
     -H "Content-Type: application/json" \
     -X POST http://localhost:8081/mcp/message \
     -d '{
           "jsonrpc":"2.0",
           "id":1,
           "method":"tools/call",
           "params":{
             "name":"getShipmentByTrackingNumber",
             "arguments":{"trackingNumber":"TRK001"}
           }
         }'
```

---

## Running with Docker

```bash
# Build image
docker build -t mcp-bridge-service:1.0.0 .

# Run container
docker run -p 8081:8081 \
  -e SUPPLY_CHAIN_ADVISOR_URL=http://host.docker.internal:8080 \
  -e API_KEY_READ=my-read-key \
  -e API_KEY_WRITE=my-write-key \
  mcp-bridge-service:1.0.0
```

---

## Security

### Authentication

Every request (except `/actuator/health` and `/actuator/info`) must include the `X-API-Key` header.

```
X-API-Key: <your-api-key>
```

| Role | Permitted Tools |
|---|---|
| `READ` | `getShipmentByTrackingNumber`, `getShipmentsBySeverity`, `getShipmentsByLocation` |
| `WRITE` | All READ tools (WRITE > READ hierarchy) + access to protected actuator endpoints |

Missing or invalid keys receive **HTTP 401**. Requests exceeding the rate limit receive **HTTP 429** with a `Retry-After: 60` header.

### Audit Log

Every tool invocation emits a structured audit log entry at `INFO` level:

```
AUDIT | tool=getShipmentsByLocation | principal=api-key-principal | roles=[ROLE_READ] | args=[Singapore] | ts=2026-05-14T10:00:00Z
AUDIT | tool=getShipmentsByLocation | status=SUCCESS | durationMs=43 | ts=2026-05-14T10:00:00Z
```

---

## Monitoring & Health

### Health check

```bash
curl http://localhost:8081/actuator/health
```

Response includes the circuit-breaker state and upstream reachability:

```json
{
  "status": "UP",
  "components": {
    "supplyChainAdvisor": { "status": "UP" },
    "circuitBreakers":    { "status": "UP" }
  }
}
```

### Prometheus metrics

```bash
curl -H "X-API-Key: my-write-key" http://localhost:8081/actuator/prometheus
```

Key metrics exposed:
- `resilience4j_circuitbreaker_state` — circuit breaker open/closed/half-open
- `resilience4j_circuitbreaker_failure_rate` — failure rate percentage
- `cache_gets_total` — Caffeine cache hit/miss counters
- Standard JVM and HTTP server metrics

---

## Testing

```bash
# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=ShipmentToolsTest
mvn test -Dtest=ApiKeyAuthFilterTest
```

Test coverage:
- `ShipmentToolsTest` — unit tests for all three MCP tools (mock client)
- `ApiKeyAuthFilterTest` — valid key, invalid key, missing key, health bypass
