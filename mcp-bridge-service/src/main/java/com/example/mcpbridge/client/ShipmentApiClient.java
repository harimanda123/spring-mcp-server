package com.example.mcpbridge.client;

import com.example.mcpbridge.model.Shipment;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

// Note: batchUpsert intentionally omitted — batch ingestion is not exposed via MCP.

/**
 * REST client for the supply-chain-advisor-service.
 *
 * <p>Each method is guarded by a Resilience4j circuit breaker named
 * {@code shipmentApi}. Configuration (thresholds, wait duration, etc.)
 * lives in {@code application.yml} under {@code resilience4j.circuitbreaker}.
 *
 * <p>{@link #getShipmentsBySeverity} additionally caches its results via
 * Caffeine to reduce load on the upstream service for repeated severity queries.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentApiClient {

    private final RestClient restClient;

    // -----------------------------------------------------------------------
    // GET /api/v1/shipments/{trackingNumber}
    // -----------------------------------------------------------------------

    @CircuitBreaker(name = "shipmentApi", fallbackMethod = "getByTrackingNumberFallback")
    public Shipment getByTrackingNumber(String trackingNumber) {
        log.debug("Fetching shipment: trackingNumber={}", trackingNumber);
        return restClient.get()
                .uri("/api/v1/shipments/{trackingNumber}", trackingNumber)
                .retrieve()
                .body(Shipment.class);
    }

    Shipment getByTrackingNumberFallback(String trackingNumber, Exception ex) {
        log.error("Circuit-breaker fallback | getByTrackingNumber | trackingNumber={} | error={}",
                trackingNumber, ex.getMessage());
        throw new ShipmentServiceUnavailableException(
                "Supply Chain Advisor Service is currently unavailable. Please try again later.");
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/shipments/severity/{severity}
    // -----------------------------------------------------------------------

    @Cacheable(value = "shipmentsBySeverity", key = "#severity")
    @CircuitBreaker(name = "shipmentApi", fallbackMethod = "getBySeverityFallback")
    public List<Shipment> getShipmentsBySeverity(String severity) {
        log.debug("Fetching shipments by severity: severity={}", severity);
        return restClient.get()
                .uri("/api/v1/shipments/severity/{severity}", severity)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    List<Shipment> getBySeverityFallback(String severity, Exception ex) {
        log.error("Circuit-breaker fallback | getShipmentsBySeverity | severity={} | error={}",
                severity, ex.getMessage());
        throw new ShipmentServiceUnavailableException(
                "Supply Chain Advisor Service is currently unavailable. Please try again later.");
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/shipments/location/{location}
    // -----------------------------------------------------------------------

    @CircuitBreaker(name = "shipmentApi", fallbackMethod = "getByLocationFallback")
    public List<Shipment> getShipmentsByLocation(String location) {
        log.debug("Fetching shipments by location: location={}", location);
        return restClient.get()
                .uri("/api/v1/shipments/location/{location}", location)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    List<Shipment> getByLocationFallback(String location, Exception ex) {
        log.error("Circuit-breaker fallback | getShipmentsByLocation | location={} | error={}",
                location, ex.getMessage());
        throw new ShipmentServiceUnavailableException(
                "Supply Chain Advisor Service is currently unavailable. Please try again later.");
    }

    // -----------------------------------------------------------------------
    // Custom exception — avoids leaking internal error details to callers
    // -----------------------------------------------------------------------

    public static class ShipmentServiceUnavailableException extends RuntimeException {
        public ShipmentServiceUnavailableException(String message) {
            super(message);
        }
    }
}
