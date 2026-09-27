package io.kellermann.tarpeisto.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read-only, current-state packing evaluation. This deliberately has no checkout or seal state:
 * those lifecycle assertions are introduced by later phases and consume this result as an
 * invalidation seam.
 */
public record PackingPreviewView(
        boolean complete,
        List<UUID> satisfiedRequirementIds,
        List<UUID> missingRequirementIds,
        List<UUID> extraAssetIds,
        List<UUID> misplacedAssetIds,
        List<ConsumableRequirementStatus> consumables) {
    public record ConsumableRequirementStatus(
            UUID requirementId,
            UUID assetModelId,
            BigDecimal requiredQuantity,
            BigDecimal observedQuantity,
            boolean satisfied) {}
}
