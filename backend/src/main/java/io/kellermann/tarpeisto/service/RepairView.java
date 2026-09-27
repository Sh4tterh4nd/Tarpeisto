package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.Condition;
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
