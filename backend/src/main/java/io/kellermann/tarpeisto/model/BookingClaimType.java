package io.kellermann.tarpeisto.model;

public enum BookingClaimType {
    ASSET,
    /** Recorded interchangeable placement; capacity is held, but checkout may bind an eligible replacement. */
    FLEXIBLE_ASSET,
    MODEL_CAPACITY,
    CONSUMABLE,
    CARRIED_CONSUMABLE
}
