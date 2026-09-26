package io.kellermann.bigcontainers.model;

/**
 * Distinguishes individually identified reusable equipment from consumable quantity stock
 * (specification section 6.2, docs/DEVELOPMENT_POLICIES.md section 4.1).
 */
public enum TrackingMode {
    SERIALIZED_ASSET,
    QUANTITY_STOCK
}
