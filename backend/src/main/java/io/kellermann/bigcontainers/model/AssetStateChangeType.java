package io.kellermann.bigcontainers.model;

/** Which of an {@link Asset}'s two independent state axes an {@link AssetStateChange} records. */
public enum AssetStateChangeType {
    CONDITION,
    LIFECYCLE
}
