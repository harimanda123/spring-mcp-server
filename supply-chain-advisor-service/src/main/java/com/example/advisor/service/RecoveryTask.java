package com.example.advisor.service;

import com.example.advisor.model.ProcessingStatus;
import com.example.advisor.model.Shipment;
import com.example.advisor.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Disaster-recovery scheduler — the "Janitor".
 *
 * <p>Runs every 60 seconds and looks for shipments whose
 * {@code processingStatus} is still {@code PENDING} but whose {@code updatedAt}
 * timestamp is older than 2 minutes. These are considered <em>stale</em>:
 * the most likely cause is a JVM crash that occurred after the ingestion
 * transaction committed but before the async intelligence thread could finish.
 *
 * <p>For each stale shipment the task re-triggers
 * {@link AdviceService#processIntelligence(List)} so no data is silently lost.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryTask {

    /** How long (minutes) a PENDING shipment may sit unprocessed before being re-queued. */
    private static final int STALE_THRESHOLD_MINUTES = 2;

    private final ShipmentRepository shipmentRepository;
    private final AdviceService       adviceService;

    @Scheduled(fixedDelay = 60_000)
    public void recoverStaleShipments() {
        LocalDateTime staleThreshold =
                LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);

        List<Shipment> stale = shipmentRepository
                .findByProcessingStatusAndUpdatedAtBefore(
                        ProcessingStatus.PENDING, staleThreshold);

        if (stale.isEmpty()) {
            log.debug("RecoveryTask: no stale shipments found.");
            return;
        }

        log.warn("RecoveryTask: re-queuing {} stale PENDING shipment(s) "
                + "(updatedAt before {}).", stale.size(), staleThreshold);

        // processIntelligence is @Async — this schedules the work without blocking
        adviceService.processIntelligence(stale);
    }
}
