package com.example.advisor.repository;

import com.example.advisor.model.ShipmentAdvice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShipmentAdviceRepository extends JpaRepository<ShipmentAdvice, Long> {

    List<ShipmentAdvice> findByStatus(String status);

    /**
     * Duplicate guard: returns {@code true} when an advice record with the same
     * tracking number and issue type was already persisted within the supplied
     * time window (typically midnight-to-midnight of the current day).
     */
    boolean existsByTrackingNumberAndIssueTypeAndCreatedAtBetween(
            String trackingNumber, String issueType,
            LocalDateTime windowStart, LocalDateTime windowEnd);
}
