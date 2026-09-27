package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.PackingRequirementView;
import java.math.BigDecimal;
import java.util.UUID;

public record PackingRequirementResponse(
        UUID id,
        String type,
        UUID assetModelId,
        UUID specificAssetId,
        BigDecimal requiredQuantity,
        int displayOrder,
        boolean archived,
        long version) {
    public static PackingRequirementResponse from(PackingRequirementView v) {
        return new PackingRequirementResponse(
                v.id(),
                v.type().name(),
                v.assetModelId(),
                v.specificAssetId(),
                v.requiredQuantity(),
                v.displayOrder(),
                v.archived(),
                v.version());
    }
}
