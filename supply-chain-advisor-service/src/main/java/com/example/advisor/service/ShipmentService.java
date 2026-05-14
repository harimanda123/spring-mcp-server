package com.example.advisor.service;

import com.example.advisor.model.ProcessingStatus;
import com.example.advisor.model.Shipment;
import com.example.advisor.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;

    /**
     * Upserts a batch of shipments and marks every record as
     * {@link ProcessingStatus#PENDING} so the async intelligence engine knows
     * it has fresh work to process.
     *
     * <p>The method is fully {@code @Transactional}: the caller receives a
     * committed list of saved entities before the async advisory engine is
     * triggered in a separate thread.
     */
    @Transactional
    public List<Shipment> batchUpsert(List<Shipment> incoming) {
        List<Shipment> results = new ArrayList<>(incoming.size());

        for (Shipment candidate : incoming) {
            Optional<Shipment> existing =
                    shipmentRepository.findByTrackingNumber(candidate.getTrackingNumber());

            if (existing.isPresent()) {
                Shipment record = existing.get();
                applyUpdates(record, candidate);
                record.setProcessingStatus(ProcessingStatus.PENDING);
                results.add(shipmentRepository.save(record));
            } else {
                candidate.setId(null);
                candidate.setProcessingStatus(ProcessingStatus.PENDING);
                results.add(shipmentRepository.save(candidate));
            }
        }

        return results;
    }

    /**
     * Returns a shipment by its tracking number.
     */
    @Transactional(readOnly = true)
    public Optional<Shipment> findByTrackingNumber(String trackingNumber) {
        return shipmentRepository.findByTrackingNumber(trackingNumber);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void applyUpdates(Shipment target, Shipment source) {
        target.setSku(source.getSku());
        target.setQuantity(source.getQuantity());
        target.setStatus(source.getStatus());
        target.setOriginHub(source.getOriginHub());
        target.setDestinationHub(source.getDestinationHub());
        target.setCurrentLocation(source.getCurrentLocation());
        target.setCarrierCode(source.getCarrierCode());
        target.setPriority(source.getPriority());
        target.setEstimatedDelivery(source.getEstimatedDelivery());
        // updatedAt is managed automatically by @UpdateTimestamp
    }
}
