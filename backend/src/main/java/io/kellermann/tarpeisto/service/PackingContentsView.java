package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.PackingContentStatus;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PackingContentsView(
        UUID containerAssetId,
        boolean complete,
        int totalAssetCount,
        int matchedCount,
        int extraCount,
        int misplacedCount,
        int inactiveCount,
        int totalRequirementCount,
        int totalConsumableCount,
        List<Requirement> requirements,
        List<Entry> assets,
        List<Consumable> consumables,
        String nextCursor) {
    public record Identity(
            UUID assetId,
            String publicCode,
            String displayName,
            UUID assetModelId,
            String assetModelName,
            boolean active) {}

    public record Requirement(
            UUID id,
            PackingRequirementType type,
            UUID assetModelId,
            String assetModelName,
            String unitLabel,
            BigDecimal requiredQuantity,
            BigDecimal presentQuantity,
            BigDecimal missingQuantity,
            boolean satisfied,
            Identity exactAsset) {}

    public record Entry(Identity asset, PackingContentStatus status, UUID requirementId) {}

    public record Consumable(
            UUID assetModelId, String assetModelName, String unitLabel, BigDecimal quantity, boolean archived) {}
}
