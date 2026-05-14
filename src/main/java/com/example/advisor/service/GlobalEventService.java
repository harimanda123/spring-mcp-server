package com.example.advisor.service;

import com.example.advisor.model.GlobalEvent;
import com.example.advisor.repository.GlobalEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * Business logic for ERP-managed {@link GlobalEvent} records.
 *
 * <h3>Data-integrity contract</h3>
 * <ul>
 *   <li>Events are keyed on {@code (location, eventType)}.</li>
 *   <li>If the ERP pushes an event whose key matches a record that was
 *       already created by the GDACS scheduler, the ERP data <em>takes
 *       precedence</em>: severity and {@code isActive} are overwritten.</li>
 *   <li>If no matching record exists a new one is inserted with
 *       {@code isActive = true}.</li>
 * </ul>
 *
 * <h3>Intelligence trigger</h3>
 * After each successful write, {@link AdviceService#reEvaluateImpactedShipments(GlobalEvent)}
 * is fired asynchronously by the controller for each saved event,
 * ensuring only the shipments at the event's location are re-assessed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlobalEventService {

    private final GlobalEventRepository globalEventRepository;

    // -----------------------------------------------------------------------
    // Create / Update
    // -----------------------------------------------------------------------

    /**
     * Upserts one or more ERP-reported events, then triggers async shipment
     * re-evaluation for each batch.
     *
     * @param events the events to persist (must not be null)
     * @return the saved / updated entities in the same order
     */
    @Transactional
    public List<GlobalEvent> createOrUpdateEvents(List<GlobalEvent> events) {
        List<GlobalEvent> saved = events.stream()
                .map(this::upsertSingle)
                .toList();

        log.info("GlobalEventService: {} event(s) upserted.", saved.size());

        return saved;
    }

    // -----------------------------------------------------------------------
    // Deactivate (soft-delete)
    // -----------------------------------------------------------------------

    /**
     * Marks the event as inactive ({@code isActive = false}).
     *
     * <p>A soft-delete is preferred over a hard-delete so that historical event
     * data is preserved for auditing and the GDACS scheduler can still re-activate
     * the same record if the disaster resumes.
     *
     * @param id the primary key of the event to deactivate
     * @throws NoSuchElementException if no event exists for the given id
     */
    @Transactional
    public void deactivateEvent(Long id) {
        GlobalEvent event = globalEventRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "GlobalEvent not found for id: " + id));
        event.setIsActive(false);
        globalEventRepository.save(event);
        log.info("GlobalEvent id={} ({} @ {}) deactivated.", id,
                event.getEventType(), event.getLocation());
    }

    // -----------------------------------------------------------------------
    // Private helper
    // -----------------------------------------------------------------------

    /**
     * Upserts a single event:
     * <ul>
     *   <li>Existing record (same location + type): ERP severity and isActive win.</li>
     *   <li>No existing record: insert with {@code isActive = true}.</li>
     * </ul>
     */
    private GlobalEvent upsertSingle(GlobalEvent incoming) {
        return globalEventRepository
                .findByLocationIgnoreCaseAndEventType(
                        incoming.getLocation(), incoming.getEventType())
                .map(existing -> {
                    log.info("Overriding existing GlobalEvent id={} ({} @ {}) with ERP data.",
                            existing.getId(), existing.getEventType(), existing.getLocation());
                    existing.setSeverity(incoming.getSeverity());
                    // Honour explicit isActive from ERP; default to true when not supplied
                    existing.setIsActive(
                            incoming.getIsActive() != null ? incoming.getIsActive() : Boolean.TRUE);
                    return globalEventRepository.save(existing);
                })
                .orElseGet(() -> {
                    // Strip any client-supplied id to prevent accidental overwrites
                    incoming.setId(null);
                    if (incoming.getIsActive() == null) {
                        incoming.setIsActive(Boolean.TRUE);
                    }
                    return globalEventRepository.save(incoming);
                });
    }
}
