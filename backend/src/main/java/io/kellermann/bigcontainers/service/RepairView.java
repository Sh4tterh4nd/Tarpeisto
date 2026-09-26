package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.Condition;
import java.time.Instant;
import java.util.UUID;

public record RepairView(
        UUID id,
        UUID assetId,
        UUID sourceFindingId,
        String referenceOrDescription,
        Instant openedAt,
        Instant closedAt,
        Condition resultingCondition) {}
