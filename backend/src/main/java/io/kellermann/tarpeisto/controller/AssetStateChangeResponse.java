package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.AssetStateChangeType;
import io.kellermann.tarpeisto.service.AssetStateChangeView;
import java.time.Instant;
import java.util.UUID;

/** Response element for one asset condition/lifecycle history entry. */
public record AssetStateChangeResponse(
        UUID id,
        AssetStateChangeType changeType,
        String previousValue,
        String newValue,
        String reason,
        UUID actorUserId,
        Instant changedAt) {

    public static AssetStateChangeResponse from(AssetStateChangeView view) {
        return new AssetStateChangeResponse(
                view.id(),
                view.changeType(),
                view.previousValue(),
                view.newValue(),
                view.reason(),
                view.actorUserId(),
                view.changedAt());
    }
}
