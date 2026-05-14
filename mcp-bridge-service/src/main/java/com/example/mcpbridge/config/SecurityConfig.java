package com.example.mcpbridge.config;

import com.example.mcpbridge.security.ApiKeyAuthFilter;
import com.example.mcpbridge.security.RateLimitingFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.Map;

/**
 * Spring Security configuration.
 *
 * <p>Authentication strategy: stateless API key validation via
 * {@link ApiKeyAuthFilter}. Sessions are never created. CSRF is disabled
 * because all clients are machine-to-machine (no browser cookies).
 *
 * <p>{@link EnableMethodSecurity} enables {@code @PreAuthorize} on
 * {@link com.example.mcpbridge.tools.ShipmentTools} for tool-level RBAC.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AppProperties appProperties;

    // -----------------------------------------------------------------------
    // Filter beans
    // -----------------------------------------------------------------------

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter() {
        AppProperties.Security.ApiKeys keys = appProperties.getSecurity().getApiKeys();
        Map<String, String> keyRoleMap = Map.of(
                keys.getRead(),  "READ",
                keys.getWrite(), "WRITE"
        );
        return new ApiKeyAuthFilter(keyRoleMap);
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter() {
        return new RateLimitingFilter(appProperties.getRateLimiting().getRequestsPerMinute());
    }

    // -----------------------------------------------------------------------
    // Security filter chain
    // -----------------------------------------------------------------------

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public health/info endpoints for load-balancer probes
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // All other actuator endpoints require WRITE role (ops team)
                        .requestMatchers("/actuator/**").hasRole("WRITE")
                        // All other requests require authentication (role checked per-tool)
                        .anyRequest().authenticated()
                )
                // Rate limiting runs before key validation
                .addFilterBefore(rateLimitingFilter(), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(apiKeyAuthFilter(),   UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
