package io.kellermann.tarpeisto.model;

/**
 * Physical condition of a {@link TrackingMode#SERIALIZED_ASSET} {@link Asset} (specification
 * section 8.3). Condition is separate from {@link LifecycleState} and from operational
 * availability (specification section 8.5, out of scope here).
 */
public enum Condition {
    GOOD,
    DAMAGED
}
