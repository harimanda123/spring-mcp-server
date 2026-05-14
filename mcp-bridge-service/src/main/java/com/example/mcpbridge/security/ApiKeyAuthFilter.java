package com.example.mcpbridge.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Servlet filter that validates the {@code X-API-Key} header on every request.
 *
 * <p>A valid key sets a {@link UsernamePasswordAuthenticationToken} with the
 * corresponding role (READ or WRITE) in the {@link SecurityContextHolder}.
 * Requests missing or carrying an unknown key are rejected with HTTP 401.
 *
 * <p>The filter is skipped for public actuator paths (health, info) so that
 * load-balancer health probes work without credentials.
 *
 * <p>Error responses never reveal whether the key exists or is simply missing,
 * preventing key enumeration attacks.
 */
@RequiredArgsConstructor
@Slf4j
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String API_KEY_HEADER = "X-API-Key";

    /** Map of {apiKey → role}. Built from application properties. Never mutated. */
    private final Map<String, String> apiKeyRoleMap;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader(API_KEY_HEADER);

        if (!StringUtils.hasText(apiKey)) {
            writeUnauthorized(response, "Missing X-API-Key header");
            return;
        }

        String role = apiKeyRoleMap.get(apiKey);
        if (role == null) {
            // Log only a safe prefix — never log the full key
            log.warn("Rejected invalid API key | prefix={}... | ip={}",
                    apiKey.substring(0, Math.min(6, apiKey.length())),
                    request.getRemoteAddr());
            writeUnauthorized(response, "Invalid API key");
            return;
        }

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "api-key-principal", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
        log.debug("Authenticated request | role={} | uri={}", role, request.getRequestURI());

        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.startsWith("/actuator/health") || path.startsWith("/actuator/info");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
