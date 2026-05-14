package com.example.mcpbridge.tools;

import com.example.mcpbridge.client.ShipmentApiClient;
import com.example.mcpbridge.model.Shipment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tool definitions that bridge to the Supply Chain Advisor Service.
 *
 * <p>Every public method is annotated with {@link Tool} so that the
 * {@code MethodToolCallbackProvider} in {@link com.example.mcpbridge.config.McpToolsConfig}
 * can register them with the MCP server.
 *
 * <p>Role-based access control is enforced via {@link PreAuthorize}:
 * <ul>
 *   <li>READ role  — may call the two query tools.</li>
 *   <li>WRITE role  — currently same access as READ (no write-only tools defined).</li>
 * </ul>
 *
 * <p>All invocations are audit-logged by
 * {@link com.example.mcpbridge.audit.AuditLoggingAspect}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ShipmentTools {

    private final ShipmentApiClient shipmentApiClient;

    // -----------------------------------------------------------------------
    // READ tools
    // -----------------------------------------------------------------------

    @Tool(description = """
            Retrieve a shipment from the Supply Chain Advisor system by its unique tracking number.
            Returns full shipment details: status, severity (CRITICAL/HIGH/LOW/UNKNOWN),
            current location, origin/destination hubs, SKU, quantity, estimated delivery date,
            issue type, advice message, and recommended operator action.
            Returns an error if the tracking number does not exist.
            """)
    @PreAuthorize("hasAnyRole('READ','WRITE')")
    public Shipment getShipmentByTrackingNumber(
            @ToolParam(description = "Unique shipment tracking number, e.g. TRK001")
            String trackingNumber) {
        return shipmentApiClient.getByTrackingNumber(trackingNumber);
    }

    @Tool(description = """
            Retrieve all shipments that match a given severity level in the Supply Chain Advisor system.
            Valid values for severity: CRITICAL (RED alert), HIGH (ORANGE alert), LOW (GREEN alert), UNKNOWN.
            Returns an empty list when no shipments match — callers can distinguish this from a
            "not found" error. Results are cached for 5 minutes to reduce upstream load.
            """)
    @PreAuthorize("hasAnyRole('READ','WRITE')")
    public List<Shipment> getShipmentsBySeverity(
            @ToolParam(description = "Severity level to filter by. Valid values: CRITICAL, HIGH, LOW, UNKNOWN")
            String severity) {
        return shipmentApiClient.getShipmentsBySeverity(severity.toUpperCase());
    }

    @Tool(description = """
            Retrieve all shipments associated with a specific location from the Supply Chain Advisor system.
            Matches against both the shipment's current location and its destination hub (case-insensitive).
            Returns an empty list when no shipments are found for the given location.
            Useful for assessing supply-chain exposure at a hub or city affected by a disruption event.
            """)
    @PreAuthorize("hasAnyRole('READ','WRITE')")
    public List<Shipment> getShipmentsByLocation(
            @ToolParam(description = "Hub or city name to search, e.g. Singapore, Rotterdam, Shanghai. " +
                    "Matched against currentLocation and destinationHub (case-insensitive).")
            String location) {
        return shipmentApiClient.getShipmentsByLocation(location);
    }
}
