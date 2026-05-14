package com.example.advisor.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * JPA entity representing a shipment in transit.
 * The H2 dependency is scoped to runtime; replacing it with any RDBMS driver
 * (e.g. PostgreSQL, MySQL) and updating application.properties is sufficient
 * to target a different database without touching this class.
 */
@Entity
@Table(
    name = "shipments",
    indexes = @Index(name = "idx_tracking_number", columnList = "tracking_number", unique = true)
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Shipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_number", nullable = false, unique = true)
    private String trackingNumber;

    @Column(name = "sku")
    private String sku;

    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "status")
    private String status;

    @Column(name = "origin_hub")
    private String originHub;

    @Column(name = "destination_hub")
    private String destinationHub;

    @Column(name = "current_location")
    private String currentLocation;

    @Column(name = "carrier_code")
    private String carrierCode;

    @Column(name = "priority")
    private String priority;

    @Column(name = "estimated_delivery")
    private LocalDate estimatedDelivery;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Tracks where the background intelligence engine is in its analysis lifecycle.
     * Set to PENDING on ingest; transitioned to COMPLETED or FAILED by AdviceService.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    @Builder.Default
    private ProcessingStatus processingStatus = ProcessingStatus.PENDING;

    /** Timestamp of the most recent successful intelligence-engine run for this shipment. */
    @Column(name = "last_processed_at")
    private LocalDateTime lastProcessedAt;

    // -----------------------------------------------------------------------
    // Intelligence result fields — populated by AdviceService
    // -----------------------------------------------------------------------

    /** Issue category derived from the matching GlobalEvent (e.g. GLOBAL_EVENT_EARTHQUAKE). */
    @Column(name = "issue_type")
    private String issueType;

    /** Human-readable advice message produced by the intelligence engine. */
    @Column(name = "advice_message", length = 1024)
    private String adviceMessage;

    /**
     * Dynamic severity mapped from the GlobalEvent alert level.
     * Values: {@code CRITICAL} (RED alert), {@code HIGH} (ORANGE alert), {@code LOW} (GREEN alert).
     */
    @Column(name = "severity")
    private String severity;

    /** Recommended operator action mapped from the GlobalEvent alert level. */
    @Column(name = "recommended_action", length = 512)
    private String recommendedAction;
}
