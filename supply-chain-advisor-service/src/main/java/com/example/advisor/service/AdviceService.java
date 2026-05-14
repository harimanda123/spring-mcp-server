package com.example.advisor.service;

import com.example.advisor.model.GlobalEvent;
import com.example.advisor.model.ProcessingStatus;
import com.example.advisor.model.Shipment;
import com.example.advisor.model.ShipmentAdvice;
import com.example.advisor.repository.GlobalEventRepository;
import com.example.advisor.repository.ShipmentAdviceRepository;
import com.example.advisor.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdviceService {

    /** Number of impacted shipments processed per database page during targeted re-evaluation. */
    private static final int BATCH_SIZE = 500;

    private final ShipmentAdviceRepository adviceRepository;
    private final ShipmentRepository       shipmentRepository;
    private final GlobalEventRepository    globalEventRepository;

    // -----------------------------------------------------------------------
    // Async Intelligence Engine
    // -----------------------------------------------------------------------

    /**
     * Entry point for the background advisory engine.
     * Called by {@code ShipmentController} after the ingestion transaction commits
     * and by {@code RecoveryTask} for stale PENDING shipments.
     *
     * <p>Each shipment is processed independently so a failure on one record does
     * not prevent the remaining records from being analysed.
     *
     * @param shipments list of freshly-saved (PENDING) shipments to analyse
     */
    @Async("intelligenceExecutor")
    public void processIntelligence(List<Shipment> shipments) {
        runIntelligencePipeline(shipments);
    }

    /**
     * Re-evaluates every in-transit shipment against the current set of active
     * {@link GlobalEvent} records.
     *
     * <p>Triggered automatically whenever the ERP pushes a new event via
     * {@code POST /api/v1/events}, ensuring that shipments already in the system
     * are immediately assessed against the newly registered disruption.
     *
     * <p>The daily duplicate-guard inside {@link #saveAdviceIfNew} prevents the
     * same advice being generated twice for the same shipment on the same day.
     */
    @Async("intelligenceExecutor")
    public void reEvaluateAllShipments() {
        List<Shipment> active = shipmentRepository.findByStatusNot("DELIVERED");
        log.info("Re-evaluation triggered: {} active shipment(s) will be assessed.",
                active.size());
        runIntelligencePipeline(active);
    }

    // -----------------------------------------------------------------------    // Async Intelligence Engine — targeted path (new ERP event)
    // -----------------------------------------------------------------------

    /**
     * High-performance targeted re-evaluation triggered whenever the ERP registers
     * a new {@link GlobalEvent}.
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li><b>DB query (Step A)</b> — fetches only the shipments whose
     *       {@code currentLocation} or {@code destinationHub} matches the event's
     *       location, avoiding a full-table scan.</li>
     *   <li><b>Batching (Step B)</b> — iterates results in pages of {@value #BATCH_SIZE}
     *       rows so heap pressure stays constant regardless of dataset size.</li>
     *   <li><b>Idempotent advice (Step C)</b> — the same-day duplicate guard inside
     *       {@link #saveAdviceIfNew} ensures pushing the same event twice never
     *       creates duplicate {@link ShipmentAdvice} records.</li>
     * </ol>
     *
     * <h3>Efficiency</h3>
     * Active {@link GlobalEvent} records are loaded <em>once</em> into a
     * {@code Map<locationKey, List<GlobalEvent>>} before the pagination loop starts.
     * Every per-shipment cross-reference is an in-memory map lookup —
     * the {@code global_events} table is never queried inside the loop.
     *
     * @param triggerEvent the event just persisted by the ERP
     */
    @Async("intelligenceExecutor")
    public void reEvaluateImpactedShipments(GlobalEvent triggerEvent) {
        if (triggerEvent == null || triggerEvent.getLocation() == null) {
            log.warn("reEvaluateImpactedShipments called with null event or location — skipping.");
            return;
        }

        String location = triggerEvent.getLocation();
        log.info("Targeted re-evaluation started for location='{}' ({} {})",
                location, triggerEvent.getSeverity(), triggerEvent.getEventType());

        // Load ALL active events ONCE, indexed by lowercase location key.
        // Shape: Map<locationLower → List<GlobalEvent>> handles multiple events per city.
        Map<String, List<GlobalEvent>> activeEventsByLocation = globalEventRepository
                .findByIsActiveTrue()
                .stream()
                .collect(Collectors.groupingBy(e -> e.getLocation().toLowerCase()));

        // Step A + B: paginated query filtered to the impacted location
        int pageNum       = 0;
        int totalAssessed = 0;
        Page<Shipment> page;

        do {
            page = shipmentRepository.findShipmentsImpactedByLocation(
                    location, PageRequest.of(pageNum, BATCH_SIZE));

            log.debug("Processing page {}/{} ({} shipment(s)) for location='{}'",
                    pageNum + 1, page.getTotalPages(), page.getNumberOfElements(), location);

            for (Shipment shipment : page.getContent()) {
                try {
                    // Apply internal rules too — a delayed shipment at a disaster site
                    // should receive both DELAY_RISK and GLOBAL_EVENT advice
                    applyInternalRules(shipment);

                    // Step C: idempotent cross-reference via cached Map (O(1) per lookup)
                    crossReferenceWithCache(shipment, activeEventsByLocation);

                    // Dynamic severity mapping: derive CRITICAL/HIGH/LOW from the event's
                    // actual alert level and persist the result directly on the shipment
                    // entity so the ERP can read it without joining the advice table.
                    String currentLocKey = shipment.getCurrentLocation() != null
                            ? shipment.getCurrentLocation().toLowerCase() : "";
                    String destLocKey = shipment.getDestinationHub() != null
                            ? shipment.getDestinationHub().toLowerCase() : "";

                    activeEventsByLocation.entrySet().stream()
                            .filter(entry -> currentLocKey.contains(entry.getKey())
                                    || destLocKey.contains(entry.getKey()))
                            .flatMap(entry -> entry.getValue().stream())
                            .findFirst()
                            .ifPresent(event -> mapEventToShipment(event, shipment));

                    shipment.setProcessingStatus(ProcessingStatus.COMPLETED);
                    shipment.setLastProcessedAt(LocalDateTime.now());
                    shipmentRepository.save(shipment);
                    totalAssessed++;

                } catch (Exception ex) {
                    log.error("Targeted re-evaluation failed for tracking={}",
                            shipment.getTrackingNumber(), ex);
                }
            }
            pageNum++;
        } while (page.hasNext());

        log.info("Targeted re-evaluation complete: {} shipment(s) assessed for location='{}'.",
                totalAssessed, location);
    }
    

    /**
     * Triggered by {@link GlobalIntelligenceService} after a GDACS disaster event is
     * persisted. Uses {@link com.example.advisor.repository.ShipmentRepository#findShipmentsByLocationAndStatus}
     * so only IN_TRANSIT shipments at the affected location are fetched from the database —
     * the status predicate is applied at query time, preventing a full-table scan.
     *
     * <p>Results are processed in pages of {@value #BATCH_SIZE} rows so heap pressure
     * stays constant regardless of dataset size.
     *
     * @param event the GlobalEvent just saved by the GDACS intelligence feed
     */
    @Async("intelligenceExecutor")
    public void evaluateInTransitShipmentsForEvent(GlobalEvent event) {
        if (event == null || event.getLocation() == null) {
            log.warn("evaluateInTransitShipmentsForEvent called with null event/location — skipping.");
            return;
        }

        String location = event.getLocation();
        log.info("GDACS-triggered evaluation started: location='{}' severity='{}'",
                location, event.getSeverity());

        int pageNum       = 0;
        int totalAssessed = 0;
        Page<Shipment> page;

        do {
            page = shipmentRepository.findShipmentsByLocationAndStatus(
                    location, PageRequest.of(pageNum, BATCH_SIZE));

            log.debug("Processing page {}/{} ({} IN_TRANSIT shipment(s)) for location='{}'",
                    pageNum + 1, page.getTotalPages(), page.getNumberOfElements(), location);

            runIntelligencePipeline(page.getContent());
            totalAssessed += page.getNumberOfElements();
            pageNum++;
        } while (page.hasNext());

        log.info("GDACS evaluation complete: {} IN_TRANSIT shipment(s) assessed for location='{}'.",
                totalAssessed, location);
    }

    // -----------------------------------------------------------------------    // Shared processing pipeline
    // -----------------------------------------------------------------------

    /**
     * Core intelligence loop — shared by {@link #processIntelligence} and
     * {@link #reEvaluateAllShipments} to avoid code duplication and the
     * Spring same-bean {@code @Async} proxy limitation.
     *
     * <p>Each shipment is processed independently; a failure on one record never
     * prevents the others from being analysed.
     */
    private void runIntelligencePipeline(List<Shipment> shipments) {
        if (shipments == null || shipments.isEmpty()) {
            return;
        }

        // Load active global events once per batch — avoids N+1 queries
        List<GlobalEvent> activeEvents = globalEventRepository.findByIsActiveTrue();

        for (Shipment input : shipments) {
            try {
                Shipment shipment = shipmentRepository.findById(input.getId())
                        .orElse(input);

                applyInternalRules(shipment);
                crossReferenceGlobalEvents(shipment, activeEvents);

                shipment.setProcessingStatus(ProcessingStatus.COMPLETED);
                shipment.setLastProcessedAt(LocalDateTime.now());
                shipmentRepository.save(shipment);

                log.debug("Intelligence processing COMPLETED for tracking={}",
                        shipment.getTrackingNumber());

            } catch (Exception ex) {
                log.error("Intelligence processing FAILED for tracking={}",
                        input.getTrackingNumber(), ex);
                shipmentRepository.findById(input.getId()).ifPresent(s -> {
                    s.setProcessingStatus(ProcessingStatus.FAILED);
                    shipmentRepository.save(s);
                });
            }
        }
    }

    // -----------------------------------------------------------------------
    // Poll Interface
    // -----------------------------------------------------------------------

    /**
     * Returns all {@link ShipmentAdvice} records whose status is {@code PENDING}
     * and atomically marks them as {@code POLLED} within a single transaction so
     * they are never returned twice across consecutive poll cycles.
     */
    @Transactional
    public List<ShipmentAdvice> pollPendingAdvice() {
        List<ShipmentAdvice> pending = adviceRepository.findByStatus("PENDING");

        if (!pending.isEmpty()) {
            pending.forEach(a -> a.setStatus("POLLED"));
            adviceRepository.saveAll(pending);
        }

        return pending;
    }

    // -----------------------------------------------------------------------
    // Private rule implementations
    // -----------------------------------------------------------------------

    /** Applies stateless internal rules that need no external data. */
    private void applyInternalRules(Shipment shipment) {

        // Rule 1 — High-priority shipment that is currently delayed
        if ("HIGH".equalsIgnoreCase(shipment.getPriority())
                && "DELAYED".equalsIgnoreCase(shipment.getStatus())) {
            saveAdviceIfNew(
                    shipment,
                    "DELAY_RISK",
                    "CRITICAL",
                    "High priority shipment is delayed. Immediate action required for SKU "
                            + shipment.getSku() + ".",
                    "Contact carrier immediately and arrange express re-routing. "
                            + "Notify the receiving warehouse of the revised ETA.");
        }

        // Rule 2 — Estimated delivery date has passed and shipment is not yet delivered
        if (shipment.getEstimatedDelivery() != null
                && shipment.getEstimatedDelivery().isBefore(LocalDate.now())
                && !"DELIVERED".equalsIgnoreCase(shipment.getStatus())) {
            saveAdviceIfNew(
                    shipment,
                    "OVERDUE",
                    "WARNING",
                    "Shipment " + shipment.getTrackingNumber()
                            + " has passed its estimated delivery date and has not been delivered.",
                    "Initiate delivery-exception process. Notify the customer and escalate "
                            + "to the logistics manager for resolution.");
        }
    }

    /**
     * Cross-references the shipment's geographic footprint against all currently
     * active global events sourced from GDACS.
     */
    private void crossReferenceGlobalEvents(Shipment shipment, List<GlobalEvent> activeEvents) {
        // Build a set of location strings associated with this shipment
        String currentLocation  = shipment.getCurrentLocation();
        String destinationHub   = shipment.getDestinationHub();

        for (GlobalEvent event : activeEvents) {
            String eventLoc = event.getLocation().toLowerCase();

            boolean atRisk =
                    (currentLocation != null && currentLocation.toLowerCase().contains(eventLoc))
                    || (destinationHub  != null && destinationHub.toLowerCase().contains(eventLoc));

            if (atRisk) {
                saveAdviceIfNew(
                        shipment,
                        "GLOBAL_EVENT_" + event.getEventType().toUpperCase(),
                        "CRITICAL",
                        "Active " + event.getSeverity() + " alert: " + event.getEventType()
                                + " detected near shipment location '" + event.getLocation() + "'.",
                        "Reroute shipment to avoid the affected area. "
                                + "Contact carrier for alternative routing and update ETA.");
            }
        }
    }

    /**
     * High-performance variant of {@link #crossReferenceGlobalEvents} used by the
     * targeted re-evaluation path.
     *
     * <p>Active events are supplied as a pre-built
     * {@code Map<locationKey, List<GlobalEvent>>} so each per-shipment lookup is
     * O(1) rather than O(events), eliminating repeated linear scans when thousands
     * of shipments are evaluated against many concurrent active events.
     */
    private void crossReferenceWithCache(Shipment shipment,
                                          Map<String, List<GlobalEvent>> activeEventsByLocation) {
        String currentLocation = shipment.getCurrentLocation();
        String destinationHub  = shipment.getDestinationHub();

        activeEventsByLocation.forEach((eventLocKey, events) -> {
            boolean atRisk =
                    (currentLocation != null && currentLocation.toLowerCase().contains(eventLocKey))
                    || (destinationHub  != null && destinationHub.toLowerCase().contains(eventLocKey));

            if (atRisk) {
                for (GlobalEvent event : events) {
                    saveAdviceIfNew(
                            shipment,
                            "GLOBAL_EVENT_" + event.getEventType().toUpperCase(),
                            "CRITICAL",
                            "Active " + event.getSeverity() + " alert: " + event.getEventType()
                                    + " detected near shipment location '"
                                    + event.getLocation() + "'.",
                            "Reroute shipment to avoid the affected area. "
                                    + "Contact carrier for alternative routing and update ETA.");
                }
            }
        });
    }

    /**
     * Maps a {@link GlobalEvent}'s alert level to a human-readable severity tier
     * and a recommended operator action, writing the results directly onto the
     * {@link Shipment} entity so they are persisted alongside the shipment record.
     *
     * <table border="1">
     *   <tr><th>Alert Level</th><th>Severity</th><th>Recommended Action</th></tr>
     *   <tr><td>RED</td><td>CRITICAL</td><td>Immediate Reroute Required.</td></tr>
     *   <tr><td>ORANGE</td><td>HIGH</td><td>Plan alternative routing.</td></tr>
     *   <tr><td>GREEN</td><td>LOW</td><td>Monitor situation; no action required.</td></tr>
     * </table>
     */
    private void mapEventToShipment(GlobalEvent event, Shipment shipment) {
        String alertLevel = event.getSeverity() != null ? event.getSeverity().toUpperCase() : "";
        switch (alertLevel) {
            case "RED" -> {
                shipment.setSeverity("CRITICAL");
                shipment.setRecommendedAction("Immediate Reroute Required.");
            }
            case "ORANGE" -> {
                shipment.setSeverity("HIGH");
                shipment.setRecommendedAction("Plan alternative routing.");
            }
            case "GREEN" -> {
                shipment.setSeverity("LOW");
                shipment.setRecommendedAction("Monitor situation; no action required.");
            }
            default -> {
                shipment.setSeverity("UNKNOWN");
                shipment.setRecommendedAction("Review event details manually.");
            }
        }
        shipment.setIssueType("GLOBAL_EVENT_" + event.getEventType().toUpperCase());
        shipment.setAdviceMessage("Active " + event.getSeverity() + " alert: "
                + event.getEventType() + " detected near shipment location '"
                + event.getLocation() + "'.");
        log.debug("Intelligence mapped: tracking={} severity={} issueType={}",
                shipment.getTrackingNumber(), shipment.getSeverity(), shipment.getIssueType());
    }

    /**
     * Persists a new {@link ShipmentAdvice} only if no identical
     * (same tracking number + same issue type) record was already created today.
     * This is the idempotency guard — pushing the same event twice in one day
     * never floods the ERP with duplicate advice records.
     */
    private void saveAdviceIfNew(Shipment shipment, String issueType, String severity,
                                  String message, String recommendedAction) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay   = startOfDay.plusDays(1);

        boolean alreadyExists = adviceRepository
                .existsByTrackingNumberAndIssueTypeAndCreatedAtBetween(
                        shipment.getTrackingNumber(), issueType, startOfDay, endOfDay);

        if (!alreadyExists) {
            ShipmentAdvice advice = ShipmentAdvice.builder()
                    .trackingNumber(shipment.getTrackingNumber())
                    .issueType(issueType)
                    .severity(severity)
                    .adviceMessage(message)
                    .recommendedAction(recommendedAction)
                    .build();
            adviceRepository.save(advice);
            log.info("Advice created: tracking={} issueType={} severity={}",
                    shipment.getTrackingNumber(), issueType, severity);
        }
    }
}
