package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.PackingContentStatus;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import io.kellermann.tarpeisto.service.PackingContentsView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Whole-container counts with at most 100 rows in each paginated collection. */
public record PackingContentsResponse(
        UUID containerAssetId,
        boolean complete,
        int totalAssetCount,
        int matchedCount,
        int extraCount,
        int misplacedCount,
        int inactiveCount,
        int totalRequirementCount,
        int totalConsumableCount,
        List<PackingContentRequirementResponse> requirements,
        List<PackingContentEntryResponse> assets,
        List<PackingContentConsumableResponse> consumables,
        String nextCursor) {
    public static PackingContentsResponse from(PackingContentsView view) {
        return new PackingContentsResponse(
                view.containerAssetId(),
                view.complete(),
                view.totalAssetCount(),
                view.matchedCount(),
                view.extraCount(),
                view.misplacedCount(),
                view.inactiveCount(),
                view.totalRequirementCount(),
                view.totalConsumableCount(),
                view.requirements().stream()
                        .map(row -> new PackingContentRequirementResponse(
                                row.id(),
                                row.type(),
                                row.assetModelId(),
                                row.assetModelName(),
                                row.unitLabel(),
                                row.requiredQuantity(),
                                row.presentQuantity(),
                                row.missingQuantity(),
                                row.satisfied(),
                                PackingContentIdentityResponse.from(row.exactAsset())))
                        .toList(),
                view.assets().stream()
                        .map(row -> new PackingContentEntryResponse(
                                PackingContentIdentityResponse.from(row.asset()), row.status(), row.requirementId()))
                        .toList(),
                view.consumables().stream()
                        .map(row -> new PackingContentConsumableResponse(
                                row.assetModelId(),
                                row.assetModelName(),
                                row.unitLabel(),
                                row.quantity(),
                                row.archived()))
                        .toList(),
                view.nextCursor());
    }

    public record PackingContentIdentityResponse(
            UUID assetId,
            String publicCode,
            String displayName,
            UUID assetModelId,
            String assetModelName,
            boolean active) {
        static PackingContentIdentityResponse from(PackingContentsView.Identity asset) {
            return asset == null
                    ? null
                    : new PackingContentIdentityResponse(
                            asset.assetId(),
                            asset.publicCode(),
                            asset.displayName(),
                            asset.assetModelId(),
                            asset.assetModelName(),
                            asset.active());
        }
    }

    public record PackingContentRequirementResponse(
            UUID id,
            PackingRequirementType type,
            UUID assetModelId,
            String assetModelName,
            String unitLabel,
            BigDecimal requiredQuantity,
            BigDecimal presentQuantity,
            BigDecimal missingQuantity,
            boolean satisfied,
            PackingContentIdentityResponse exactAsset) {}

    public record PackingContentEntryResponse(
            PackingContentIdentityResponse asset, PackingContentStatus status, UUID requirementId) {}

    public record PackingContentConsumableResponse(
            UUID assetModelId, String assetModelName, String unitLabel, BigDecimal quantity, boolean archived) {}
}
