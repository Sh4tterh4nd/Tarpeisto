package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.PackingPreviewView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PackingPreviewResponse(
        boolean complete,
        List<UUID> satisfiedRequirementIds,
        List<UUID> missingRequirementIds,
        List<UUID> extraAssetIds,
        List<UUID> misplacedAssetIds,
        List<ConsumableStatus> consumables) {
    public static PackingPreviewResponse from(PackingPreviewView view) {
        return new PackingPreviewResponse(
                view.complete(),
                view.satisfiedRequirementIds(),
                view.missingRequirementIds(),
                view.extraAssetIds(),
                view.misplacedAssetIds(),
                view.consumables().stream().map(ConsumableStatus::from).toList());
    }

    public record ConsumableStatus(
            UUID requirementId,
            UUID assetModelId,
            BigDecimal requiredQuantity,
            BigDecimal observedQuantity,
            boolean satisfied) {
        private static ConsumableStatus from(PackingPreviewView.ConsumableRequirementStatus value) {
            return new ConsumableStatus(
                    value.requirementId(),
                    value.assetModelId(),
                    value.requiredQuantity(),
                    value.observedQuantity(),
                    value.satisfied());
        }
    }
}
