package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.AssetStateChange;
import io.kellermann.bigcontainers.model.AssetStateChangeType;
import java.time.Instant;
import java.util.UUID;

/** Read projection of one {@link AssetStateChange} history entry. */
public record AssetStateChangeView(
        UUID id,
        AssetStateChangeType changeType,
        String previousValue,
        String newValue,
        String reason,
        UUID actorUserId,
        Instant changedAt) {

    public static AssetStateChangeView from(AssetStateChange change) {
        return new AssetStateChangeView(
                change.getId(),
                change.getChangeType(),
                change.getPreviousValue(),
                change.getNewValue(),
                change.getReason(),
                change.getActorUserId(),
                change.getChangedAt());
    }
}
