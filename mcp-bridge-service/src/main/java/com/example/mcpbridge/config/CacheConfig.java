package com.example.mcpbridge.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine-backed cache configuration.
 *
 * <p>Cache names:
 * <ul>
 *   <li>{@code shipmentsBySeverity} — caches {@code GET /severity/{severity}} responses
 *       for 5 minutes with a maximum of 200 entries. Entries are evicted eagerly on
 *       every successful batch-upsert to keep severity results fresh.</li>
 * </ul>
 */
@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("shipmentsBySeverity");
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(200)
                .recordStats());
        return manager;
    }
}
