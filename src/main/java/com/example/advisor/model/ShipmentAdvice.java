package com.example.advisor.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "shipment_advice",
    indexes = @Index(name = "idx_advice_status", columnList = "status")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShipmentAdvice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tracking_number", nullable = false)
    private String trackingNumber;

    @Column(name = "issue_type")
    private String issueType;

    @Column(name = "advice_message", length = 1024)
    private String adviceMessage;

    /** CRITICAL | WARNING | INFO */
    @Column(name = "severity", nullable = false)
    private String severity;

    /** PENDING (default) | POLLED */
    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "PENDING";

    /** Concrete next-step instruction surfaced to the ERP operator. */
    @Column(name = "recommended_action", length = 1024)
    private String recommendedAction;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
