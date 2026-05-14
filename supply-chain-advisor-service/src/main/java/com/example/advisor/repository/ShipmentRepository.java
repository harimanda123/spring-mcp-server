package com.example.advisor.repository;

import com.example.advisor.model.ProcessingStatus;
import com.example.advisor.model.Shipment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    /** Used by the intelligence engine and recovery task to find unprocessed shipments. */
    List<Shipment> findByProcessingStatus(ProcessingStatus processingStatus);

    /**
     * Finds shipments that are stuck in PENDING state beyond the given timestamp threshold.
     * The recovery scheduler uses this to re-queue stale work after a JVM crash.
     */
    List<Shipment> findByProcessingStatusAndUpdatedAtBefore(
            ProcessingStatus processingStatus, LocalDateTime threshold);

    /**
     * Returns all shipments whose business status is NOT the supplied value.
     * Used by re-evaluation logic to select every in-transit / non-delivered shipment.
     */
    List<Shipment> findByStatusNot(String status);

    /**
     * Targeted impact query: returns a page of shipments whose current location
     * or destination hub matches the given event location (case-insensitive).
     *
     * <p>Used by {@code AdviceService.reEvaluateImpactedShipments} so only genuinely
     * at-risk shipments are loaded — not the entire shipments table.
     */
    @Query("SELECT s FROM Shipment s "
            + "WHERE LOWER(s.currentLocation) = LOWER(:location) "
            + "   OR LOWER(s.destinationHub)  = LOWER(:location)")
    Page<Shipment> findShipmentsImpactedByLocation(
            @Param("location") String location, Pageable pageable);

    /**
     * Database-level filter: returns a page of {@code IN_TRANSIT} shipments whose
     * {@code currentLocation} or {@code destinationHub} matches the given location
     * (case-insensitive).
     *
     * <p>The {@code status = 'IN_TRANSIT'} predicate is evaluated by the database engine,
     * so only genuinely at-risk shipments are transferred over the wire — preventing
     * a full-table scan and heap pressure on large datasets.
     *
     * <p>Used by
     * {@link com.example.advisor.service.AdviceService#evaluateInTransitShipmentsForEvent}
     * after a GDACS disaster event is persisted.
     */
    @Query("SELECT s FROM Shipment s "
            + "WHERE (LOWER(s.currentLocation) = LOWER(:location) "
            + "    OR LOWER(s.destinationHub)  = LOWER(:location)) "
            + "  AND s.status = 'IN_TRANSIT'")
    Page<Shipment> findShipmentsByLocationAndStatus(
            @Param("location") String location, Pageable pageable);

    /**
     * Returns all shipments whose intelligence-engine severity matches the supplied
     * value (case-insensitive). Typical values: {@code CRITICAL}, {@code HIGH},
     * {@code LOW}, {@code UNKNOWN}.
     */
    List<Shipment> findBySeverityIgnoreCase(String severity);

    /**
     * Returns all shipments whose {@code currentLocation} OR {@code destinationHub}
     * matches the given location (case-insensitive).
     *
     * <p>Used by {@code GET /api/v1/shipments/location/{location}} to surface every
     * shipment at or heading to the queried hub without loading the full table.
     */
    List<Shipment> findByCurrentLocationIgnoreCaseOrDestinationHubIgnoreCase(
            String currentLocation, String destinationHub);
}
