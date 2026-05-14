package com.example.mcpbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralised configuration properties bound from the {@code app.*} namespace
 * in {@code application.yml}. Injected wherever needed via constructor injection.
 */
@ConfigurationProperties(prefix = "app")
@Data
public class AppProperties {

    private SupplyChainAdvisor supplyChainAdvisor = new SupplyChainAdvisor();
    private Security security = new Security();
    private RateLimiting rateLimiting = new RateLimiting();

    @Data
    public static class SupplyChainAdvisor {
        /** Base URL of the downstream supply-chain-advisor-service. */
        private String baseUrl = "http://localhost:8080";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
    }

    @Data
    public static class Security {
        private ApiKeys apiKeys = new ApiKeys();

        @Data
        public static class ApiKeys {
            /** API key granting READ access to shipment query tools. */
            private String read;
            /** API key granting WRITE access (all tools including batch upsert). */
            private String write;
        }
    }

    @Data
    public static class RateLimiting {
        /** Maximum requests allowed per API key per minute. */
        private int requestsPerMinute = 60;
    }
}
