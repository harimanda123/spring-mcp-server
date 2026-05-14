package com.example.advisor.controller;

import com.example.advisor.model.GlobalEvent;
import com.example.advisor.repository.GlobalEventRepository;
import com.example.advisor.service.AdviceService;
import com.example.advisor.service.GlobalEventService;
import com.example.advisor.service.GlobalIntelligenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * REST interface for ERP-managed {@link GlobalEvent} records.
 *
 * <p>The ERP uses these endpoints to:
 * <ul>
 *   <li><b>POST</b> — register one or more local/global disruptions (port closures,
 *       strikes, natural disasters) that are not yet in the GDACS public feed.</li>
 *   <li><b>DELETE</b> — mark a disruption as resolved so the advisory engine stops
 *       generating alerts for it.</li>
 * </ul>
 *
 * Every successful {@code POST} immediately triggers an asynchronous re-evaluation
 * of all in-transit shipments against the full set of active global events.
 */
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
@Tag(name = "Global Events", description = "ERP interface for registering and resolving supply-chain disruption events")
public class GlobalEventController {

    private final GlobalEventService        globalEventService;
    private final AdviceService             adviceService;
    private final GlobalEventRepository     globalEventRepository;
    private final GlobalIntelligenceService globalIntelligenceService;

    // -----------------------------------------------------------------------
    // GET /api/v1/events
    // -----------------------------------------------------------------------

    /**
     * Returns every {@link GlobalEvent} record currently stored in the database,
     * both active and inactive, for full visibility into the event registry.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "List all global events",
        description = "Returns all GlobalEvent records (active and inactive) stored in the database.",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Full event list",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = GlobalEvent.class)))
            )
        }
    )
    public ResponseEntity<List<GlobalEvent>> listAllEvents() {
        return ResponseEntity.ok(globalEventRepository.findAll());
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/events/trigger-fetch
    // -----------------------------------------------------------------------

    /**
     * Manually triggers the GDACS RSS fetch and re-evaluation cycle.
     * Useful for diagnostics and on-demand updates outside the hourly cron schedule.
     */
    @GetMapping(value = "/trigger-fetch", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Manually trigger the GDACS intelligence fetch",
        description = """
                Runs the same logic as the hourly scheduler: fetches the GDACS RSS feed, \
                matches Red/Orange alerts against known shipment locations, and upserts \
                GlobalEvent records. Returns immediately after initiating the fetch.""",
        responses = {
            @ApiResponse(responseCode = "200", description = "Fetch initiated",
                         content = @Content(schema = @Schema(implementation = String.class)))
        }
    )
    public ResponseEntity<String> triggerFetch() {
        globalIntelligenceService.updateGlobalRisks();
        return ResponseEntity.ok("Public risk fetch initiated and re-evaluation triggered.");
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/events
    // -----------------------------------------------------------------------

    /**
     * Accepts one or more disruption events from the ERP and persists them.
     *
     * <p><b>Upsert semantics</b>: if a record with the same {@code location} and
     * {@code eventType} already exists (e.g. one sourced from the GDACS scheduler),
     * the ERP data takes precedence and the existing record is updated.
     *
     * <p>After the transaction commits, asynchronous shipment re-evaluation is
     * triggered automatically — no additional API call is required.
     */
    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Register disruption event(s)",
        description = """
                Upserts one or more global/local disruption events. \
                If an event with the same location + eventType already exists it is overwritten \
                (ERP data takes precedence). \
                Async shipment re-evaluation is triggered automatically after each batch.""",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = GlobalEvent.class)))
        ),
        responses = {
            @ApiResponse(
                responseCode = "201",
                description = "Events persisted; shipment re-evaluation running in background",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = GlobalEvent.class)))
            )
        }
    )
    public ResponseEntity<List<GlobalEvent>> createEvents(
            @RequestBody List<GlobalEvent> events) {

        if (events == null || events.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Request body must contain at least one event.");
        }

        List<GlobalEvent> saved = globalEventService.createOrUpdateEvents(events);

        // Trigger a targeted async re-evaluation for each saved event — only shipments
        // at the event's location are queried and assessed (Step A), in pages of 500
        // (Step B), with the same-day duplicate guard preventing redundant advice (Step C).
        saved.forEach(adviceService::reEvaluateImpactedShipments);

        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // -----------------------------------------------------------------------
    // DELETE /api/v1/events/{id}
    // -----------------------------------------------------------------------

    /**
     * Deactivates (soft-deletes) an event once the disruption is resolved.
     *
     * <p>The record is retained for audit purposes but {@code isActive} is set to
     * {@code false}, so the advisory engine will no longer generate alerts for
     * shipments at that location. The GDACS scheduler may re-activate the record
     * if the same event resurfaces in the public feed.
     */
    @DeleteMapping("/{id}")
    @Operation(
        summary = "Resolve / deactivate a disruption event",
        description = """
                Marks the specified event as inactive (isActive = false). \
                The record is preserved for audit purposes. \
                Active alerts for this event will no longer appear in future advice polls.""",
        parameters = @Parameter(name = "id", description = "Primary key of the GlobalEvent to deactivate", required = true),
        responses = {
            @ApiResponse(responseCode = "204", description = "Event deactivated successfully", content = @Content),
            @ApiResponse(responseCode = "404", description = "Event not found",               content = @Content)
        }
    )
    public ResponseEntity<Void> deactivateEvent(@PathVariable Long id) {
        try {
            globalEventService.deactivateEvent(id);
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        }
        return ResponseEntity.noContent().build();
    }
}
