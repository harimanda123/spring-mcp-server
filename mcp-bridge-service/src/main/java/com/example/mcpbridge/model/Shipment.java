package com.example.mcpbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * DTO mirroring the Shipment entity from the supply-chain-advisor-service.
 * Unknown JSON fields are silently ignored to remain forward-compatible with
 * any future additions to the upstream service.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Shipment {

    private Long id;
    private String trackingNumber;
    private String sku;
    private Integer quantity;
    private String status;
    private String originHub;
    private String destinationHub;
    private String currentLocation;
    private String carrierCode;
    private String priority;
    private LocalDate estimatedDelivery;
    private LocalDateTime updatedAt;

    /** PENDING | COMPLETED | FAILED — intelligence engine lifecycle. */
    private String processingStatus;
    private LocalDateTime lastProcessedAt;

    // --- Intelligence result fields populated by the advisory engine ---

    /** Issue category (e.g. GLOBAL_EVENT_EARTHQUAKE). */
    private String issueType;

    /** Human-readable advice message produced by the intelligence engine. */
    private String adviceMessage;

    /**
     * Severity mapped from the alert level:
     * CRITICAL (RED), HIGH (ORANGE), LOW (GREEN), UNKNOWN.
     */
    private String severity;

    /** Recommended operator action. */
    private String recommendedAction;
}
