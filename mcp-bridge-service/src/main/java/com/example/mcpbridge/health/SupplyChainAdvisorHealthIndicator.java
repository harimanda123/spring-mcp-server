package com.example.mcpbridge.health;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Custom {@link HealthIndicator} that probes the upstream
 * supply-chain-advisor-service and surfaces its status in the
 * {@code /actuator/health} response.
 *
 * <p>A {@code DOWN} status here signals that the circuit breaker in
 * {@link com.example.mcpbridge.client.ShipmentApiClient} may be open
 * and tool calls will fall back gracefully.
 */
@Component("supplyChainAdvisor")
@RequiredArgsConstructor
@Slf4j
public class SupplyChainAdvisorHealthIndicator implements HealthIndicator {

    private final RestClient restClient;

    @Override
    public Health health() {
        try {
            restClient.get()
                    .uri("/actuator/health")
                    .retrieve()
                    .toBodilessEntity();
            return Health.up()
                    .withDetail("service", "supply-chain-advisor-service")
                    .withDetail("status", "reachable")
                    .build();
        } catch (RestClientException ex) {
            log.warn("Health check failed for supply-chain-advisor-service: {}", ex.getMessage());
            return Health.down()
                    .withDetail("service", "supply-chain-advisor-service")
                    .withDetail("error", ex.getMessage())
                    .build();
        }
    }
}
