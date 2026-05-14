package com.example.mcpbridge.audit;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;

/**
 * AOP aspect that produces a structured audit log entry for every MCP tool invocation.
 *
 * <p>Each log line captures:
 * <ul>
 *   <li>Tool (method) name</li>
 *   <li>Calling principal and assigned roles</li>
 *   <li>Sanitised argument list (avoids logging full shipment payloads in production)</li>
 *   <li>Outcome (SUCCESS / FAILURE) and wall-clock duration in milliseconds</li>
 *   <li>UTC timestamp for SIEM ingestion</li>
 * </ul>
 *
 * <p>Log level is INFO so audit events are always emitted regardless of the
 * application's debug configuration.
 */
@Aspect
@Component
@Slf4j
public class AuditLoggingAspect {

    /**
     * Intercepts all public methods in the {@code tools} package.
     */
    @Around("execution(public * com.example.mcpbridge.tools.*.*(..))")
    public Object auditToolInvocation(ProceedingJoinPoint joinPoint) throws Throwable {
        String toolName  = ((MethodSignature) joinPoint.getSignature()).getMethod().getName();
        Object[] args    = joinPoint.getArgs();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        String principal = auth != null ? auth.getName()              : "anonymous";
        String roles     = auth != null ? auth.getAuthorities().toString() : "none";
        String argSummary = sanitise(args);
        long   startMs   = System.currentTimeMillis();

        log.info("AUDIT | tool={} | principal={} | roles={} | args={} | ts={}",
                toolName, principal, roles, argSummary, Instant.now());

        try {
            Object result  = joinPoint.proceed();
            long   elapsed = System.currentTimeMillis() - startMs;
            log.info("AUDIT | tool={} | status=SUCCESS | durationMs={} | ts={}",
                    toolName, elapsed, Instant.now());
            return result;

        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("AUDIT | tool={} | status=FAILURE | errorType={} | durationMs={} | ts={}",
                    toolName, ex.getClass().getSimpleName(), elapsed, Instant.now());
            throw ex;
        }
    }

    /**
     * Returns a safe representation of the arguments.
     * Lists are summarised as their size to avoid flooding logs with large payloads.
     */
    private String sanitise(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        Object[] sanitised = Arrays.stream(args)
                .map(a -> (a instanceof java.util.List<?> list)
                        ? "List[size=" + list.size() + "]"
                        : a)
                .toArray();
        return Arrays.toString(sanitised);
    }
}
