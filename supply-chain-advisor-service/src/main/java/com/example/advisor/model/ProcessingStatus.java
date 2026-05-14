package com.example.advisor.model;

/** Lifecycle status of the background intelligence processing for a {@link Shipment}. */
public enum ProcessingStatus {
    /** Ingested but not yet analysed by the intelligence engine. */
    PENDING,
    /** All advisory rules have been evaluated and advice records persisted. */
    COMPLETED,
    /** Analysis failed; the recovery scheduler will retry. */
    FAILED
}
