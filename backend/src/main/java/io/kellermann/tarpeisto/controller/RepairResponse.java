package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.Condition;
import io.kellermann.tarpeisto.service.RepairView;
import java.time.Instant;
import java.util.UUID;

public record RepairResponse(
        UUID id,
        UUID assetId,
        UUID sourceFindingId,
        String referenceOrDescription,
        Instant openedAt,
        Instant closedAt,
        Condition resultingCondition) {
    static RepairResponse from(RepairView view) {
        return new RepairResponse(
                view.id(),
                view.assetId(),
                view.sourceFindingId(),
                view.referenceOrDescription(),
                view.openedAt(),
                view.closedAt(),
                view.resultingCondition());
    }
}
