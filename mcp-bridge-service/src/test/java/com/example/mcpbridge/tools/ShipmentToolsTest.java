package com.example.mcpbridge.tools;

import com.example.mcpbridge.client.ShipmentApiClient;
import com.example.mcpbridge.model.Shipment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentToolsTest {

    @Mock
    private ShipmentApiClient shipmentApiClient;

    @InjectMocks
    private ShipmentTools shipmentTools;

    // -----------------------------------------------------------------------
    // getShipmentByTrackingNumber
    // -----------------------------------------------------------------------

    @Test
    void getShipmentByTrackingNumber_returnsShipmentFromClient() {
        Shipment expected = new Shipment();
        expected.setTrackingNumber("TRK001");
        expected.setSeverity("CRITICAL");
        expected.setCurrentLocation("Singapore");
        when(shipmentApiClient.getByTrackingNumber("TRK001")).thenReturn(expected);

        Shipment result = shipmentTools.getShipmentByTrackingNumber("TRK001");

        assertThat(result.getTrackingNumber()).isEqualTo("TRK001");
        assertThat(result.getSeverity()).isEqualTo("CRITICAL");
        verify(shipmentApiClient).getByTrackingNumber("TRK001");
    }

    @Test
    void getShipmentByTrackingNumber_propagatesClientException() {
        when(shipmentApiClient.getByTrackingNumber("MISSING"))
                .thenThrow(new ShipmentApiClient.ShipmentServiceUnavailableException("Service down"));

        assertThatThrownBy(() -> shipmentTools.getShipmentByTrackingNumber("MISSING"))
                .isInstanceOf(ShipmentApiClient.ShipmentServiceUnavailableException.class)
                .hasMessageContaining("Service down");
    }

    // -----------------------------------------------------------------------
    // getShipmentsBySeverity
    // -----------------------------------------------------------------------

    @Test
    void getShipmentsBySeverity_uppercasesInput() {
        Shipment s = new Shipment();
        s.setSeverity("HIGH");
        when(shipmentApiClient.getShipmentsBySeverity("HIGH")).thenReturn(List.of(s));

        List<Shipment> result = shipmentTools.getShipmentsBySeverity("high");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSeverity()).isEqualTo("HIGH");
        // Verify the client was called with the uppercased value
        verify(shipmentApiClient).getShipmentsBySeverity("HIGH");
    }

    @Test
    void getShipmentsBySeverity_returnsEmptyListWhenNoneFound() {
        when(shipmentApiClient.getShipmentsBySeverity("LOW")).thenReturn(List.of());

        List<Shipment> result = shipmentTools.getShipmentsBySeverity("LOW");

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // getShipmentsByLocation
    // -----------------------------------------------------------------------

    @Test
    void getShipmentsByLocation_returnsMatchingShipments() {
        Shipment s1 = new Shipment();
        s1.setTrackingNumber("TRK003");
        s1.setCurrentLocation("Singapore");

        Shipment s2 = new Shipment();
        s2.setTrackingNumber("TRK004");
        s2.setDestinationHub("Singapore");

        when(shipmentApiClient.getShipmentsByLocation("Singapore")).thenReturn(List.of(s1, s2));

        List<Shipment> result = shipmentTools.getShipmentsByLocation("Singapore");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(Shipment::getTrackingNumber)
                .containsExactly("TRK003", "TRK004");
    }

    @Test
    void getShipmentsByLocation_returnsEmptyListWhenNoneFound() {
        when(shipmentApiClient.getShipmentsByLocation("UnknownHub")).thenReturn(List.of());

        List<Shipment> result = shipmentTools.getShipmentsByLocation("UnknownHub");

        assertThat(result).isEmpty();
    }

    @Test
    void getShipmentsByLocation_propagatesClientException() {
        when(shipmentApiClient.getShipmentsByLocation("Rotterdam"))
                .thenThrow(new ShipmentApiClient.ShipmentServiceUnavailableException("Service down"));

        assertThatThrownBy(() -> shipmentTools.getShipmentsByLocation("Rotterdam"))
                .isInstanceOf(ShipmentApiClient.ShipmentServiceUnavailableException.class)
                .hasMessageContaining("Service down");
    }
}
