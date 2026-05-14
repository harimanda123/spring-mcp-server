package com.example.mcpbridge.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter applied per API key.
 *
 * <p>Each API key receives its own {@link Bucket} that refills at the configured
 * {@code requestsPerMinute} rate. Requests exceeding the limit are rejected
 * immediately with HTTP 429.
 *
 * <p>Buckets are held in a {@link ConcurrentHashMap} (in-memory). For
 * multi-instance deployments, replace the map with a distributed cache backed
 * by Redis using the Bucket4j Hazelcast or Redis extension.
 */
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    private final int requestsPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitingFilter(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(ApiKeyAuthFilter.API_KEY_HEADER);
        if (!StringUtils.hasText(apiKey)) {
            // No key present — ApiKeyAuthFilter upstream will reject it;
            // let it pass through here without consuming a token.
            filterChain.doFilter(request, response);
            return;
        }

        Bucket bucket = buckets.computeIfAbsent(apiKey, this::newBucket);

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            String keyPrefix = apiKey.substring(0, Math.min(6, apiKey.length()));
            log.warn("Rate limit exceeded | keyPrefix={}... | uri={}", keyPrefix, request.getRequestURI());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "60");
            response.getWriter().write(String.format(
                    "{\"error\":\"Rate limit exceeded. Maximum %d requests per minute per API key.\"}",
                    requestsPerMinute));
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().startsWith("/actuator");
    }

    private Bucket newBucket(String apiKey) {
        Bandwidth limit = Bandwidth.classic(
                requestsPerMinute,
                Refill.intervally(requestsPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
