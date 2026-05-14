package com.example.advisor.controller;

import com.example.advisor.model.ShipmentAdvice;
import com.example.advisor.service.AdviceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@Tag(name = "Advice", description = "Advisory Engine – poll actionable recommendations for the ERP")
public class AdviceController {

    private final AdviceService adviceService;

    /**
     * GET /api/v1/shipments/advice
     *
     * Returns all {@link ShipmentAdvice} records whose status is {@code PENDING}
     * and atomically transitions them to {@code POLLED}, ensuring each record is
     * delivered exactly once across consecutive poll cycles.
     */
    @GetMapping(value = "/advice", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Poll pending shipment advice",
        description = """
                Retrieves all advisory records that have not yet been consumed (status = PENDING). \
                Each record is immediately marked as POLLED so subsequent calls do not return the \
                same advice again.""",
        responses = {
            @ApiResponse(
                responseCode = "200",
                description = "List of pending advice records (may be empty)",
                content = @Content(array = @ArraySchema(schema = @Schema(implementation = ShipmentAdvice.class)))
            )
        }
    )
    public ResponseEntity<List<ShipmentAdvice>> pollAdvice() {
        List<ShipmentAdvice> advice = adviceService.pollPendingAdvice();
        return ResponseEntity.ok(advice);
    }
}
