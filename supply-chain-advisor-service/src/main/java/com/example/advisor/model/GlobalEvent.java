package com.example.advisor.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Represents a real-world disruptive event (e.g., cyclone, flood, earthquake)
 * sourced from external intelligence feeds such as GDACS.
 * Records are upserted by {@code GlobalIntelligenceService} and cross-referenced
 * by the advisory engine to detect shipments at risk.
 */
@Entity
@Table(
    name = "global_events",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_global_event_location_type",
        columnNames = {"location", "event_type"}
    )
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Geographic area affected (country, city, or hub name). */
    @Column(nullable = false)
    private String location;

    /** Disaster category, e.g. "Cyclone", "Flood", "Earthquake". */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    /** GDACS alert level mapped to RED or ORANGE. */
    @Column(nullable = false)
    private String severity;

    /** {@code true} when the event is still ongoing and shipments should be alerted. */
    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = Boolean.FALSE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
