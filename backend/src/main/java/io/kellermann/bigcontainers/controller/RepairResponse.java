package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.service.RepairView;
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
