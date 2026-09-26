package io.kellermann.bigcontainers.model;

/**
 * Lifecycle state of a {@link TrackingMode#SERIALIZED_ASSET} {@link Asset} (specification section
 * 8.4). {@link #LOST}, {@link #DESTROYED}, and {@link #RETIRED} assets are excluded from normal
 * inventory and booking results but remain searchable through explicit filters; public asset codes
 * are never reused regardless of lifecycle state.
 */
public enum LifecycleState {
    ACTIVE,
    LOST,
    DESTROYED,
    RETIRED
}
