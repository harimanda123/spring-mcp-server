package com.example.advisor.controller;

import com.example.advisor.model.Shipment;
import com.example.advisor.repository.ShipmentRepository;
import com.example.advisor.service.AdviceService;
import com.example.advisor.service.ShipmentService;
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

@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@Tag(name = "Shipments", description = "Operations for managing shipments in the supply chain")
public class ShipmentController {

    private final ShipmentService    shipmentService;
    private final AdviceService       adviceService;
    private final ShipmentRepository  shipmentRepository;

    /**
     * POST /api/v1/shipments/batch
     *
     * Sync-Async Handshake:
     * <ol>
     *   <li>Upserts all shipments with {@code processingStatus = PENDING} inside a
     *       single {@code @Transactional} block (ShipmentService).</li>
     *   <li>Returns {@code 200 OK} immediately after the transaction commits.</li>
     *   <li>Fires {@code AdviceService.processIntelligence()} on the
     *       {@code intelligenceExecutor} thread pool — analysis happens in the
     *       background and never delays the HTTP response.</li>
     * </ol>
     */
    @PostMapping(
        value = "/batch",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Batch upsert shipments (async intelligence)",
        description = """
                Persists shipments synchronously (PENDING status) and immediately returns 200. \
                Background advisory analysis is triggered asynchronously after the DB commit.""",
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = Shipment.class)))
        ),
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Shipments persisted; intelligence analysis running in background",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = Shipment.class)))
            )
        }
    )
    public ResponseEntity<List<Shipment>> batchUpsert(@RequestBody List<Shipment> shipments) {
        // Step 1 & 2: transactional save — returns after commit
        List<Shipment> persisted = shipmentService.batchUpsert(shipments);

        // Step 3: fire-and-forget async intelligence pass (non-blocking)
        adviceService.processIntelligence(persisted);

        return ResponseEntity.ok(persisted);
    }

    /**
     * GET /api/v1/shipments/{trackingNumber}
     *
     * Retrieves a single shipment by its tracking number.
     */
    @GetMapping(value = "/{trackingNumber}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get shipment by tracking number",
        description = "Returns the shipment that matches the given tracking number.",
        parameters = @Parameter(name = "trackingNumber", description = "Unique shipment tracking number", required = true),
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Shipment found",
                content = @Content(schema = @Schema(implementation = Shipment.class))
            ),
            @ApiResponse(responseCode = "404", description = "Shipment not found", content = @Content)
        }
    )
    public ResponseEntity<Shipment> getByTrackingNumber(@PathVariable String trackingNumber) {
        return shipmentService.findByTrackingNumber(trackingNumber)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Shipment not found for trackingNumber: " + trackingNumber));
    }

    /**
     * GET /api/v1/shipments/severity/{severity}
     *
     * Returns all shipments whose intelligence-engine severity matches the given
     * value (case-insensitive). Valid values: {@code CRITICAL}, {@code HIGH},
     * {@code LOW}, {@code UNKNOWN}.
     *
     * <p>Returns an empty list (not 404) when no shipments match, so callers can
     * distinguish "no at-risk shipments" from "endpoint not found".
     */
    @GetMapping(value = "/severity/{severity}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get shipments by severity",
        description = "Returns all shipments whose intelligence severity matches the path value. "
                + "Valid values: CRITICAL, HIGH, LOW, UNKNOWN.",
        parameters = @Parameter(
            name = "severity",
            description = "Severity level assigned by the intelligence engine (case-insensitive)",
            required = true,
            example = "CRITICAL"
        ),
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Matching shipments (empty list if none found)",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = Shipment.class)))
            )
        }
    )
    public ResponseEntity<List<Shipment>> getBySeverity(@PathVariable String severity) {
        List<Shipment> results = shipmentRepository.findBySeverityIgnoreCase(severity);
        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/v1/shipments/location/{location}
     *
     * Returns all shipments whose {@code currentLocation} or {@code destinationHub}
     * matches the given location (case-insensitive). Covers both shipments currently
     * at a hub and those routed toward it.
     *
     * <p>Returns an empty list when no shipments match.
     */
    @GetMapping(value = "/location/{location}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Get shipments by location",
        description = "Returns all shipments whose current location or destination hub "
                + "matches the given location (case-insensitive).",
        parameters = @Parameter(
            name = "location",
            description = "Hub or city name to search (matches currentLocation or destinationHub)",
            required = true,
            example = "Singapore"
        ),
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Matching shipments (empty list if none found)",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = Shipment.class)))
            )
        }
    )
    public ResponseEntity<List<Shipment>> getByLocation(@PathVariable String location) {
        List<Shipment> results = shipmentRepository
                .findByCurrentLocationIgnoreCaseOrDestinationHubIgnoreCase(location, location);
        return ResponseEntity.ok(results);
    }

    /**
     * PATCH /api/v1/shipments/{trackingNumber}/severity
     *
     * Manually overrides the intelligence-engine severity for a specific shipment.
     * Accepts the new severity as a plain-text request body (e.g. {@code CRITICAL},
     * {@code HIGH}, {@code LOW}).
     *
     * <p>Returns {@code 404} if no shipment exists for the given tracking number.
     */
    @PatchMapping(
        value = "/{trackingNumber}/severity",
        consumes = MediaType.TEXT_PLAIN_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    @Operation(
        summary = "Update severity by tracking number",
        description = "Manually sets the severity field on a shipment. "
                + "Accepted values: CRITICAL, HIGH, LOW, UNKNOWN.",
        parameters = @Parameter(
            name = "trackingNumber",
            description = "Unique shipment tracking number",
            required = true
        ),
        requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = @Content(schema = @Schema(type = "string", example = "CRITICAL"))
        ),
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "Severity updated; updated shipment returned",
                content = @Content(schema = @Schema(implementation = Shipment.class))
            ),
            @ApiResponse(responseCode = "404", description = "Shipment not found", content = @Content)
        }
    )
    public ResponseEntity<Shipment> updateSeverity(
            @PathVariable String trackingNumber,
            @RequestBody String severity) {

        Shipment shipment = shipmentRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Shipment not found for trackingNumber: " + trackingNumber));

        shipment.setSeverity(severity.trim().toUpperCase());
        Shipment saved = shipmentRepository.save(shipment);
        return ResponseEntity.ok(saved);
    }
}
