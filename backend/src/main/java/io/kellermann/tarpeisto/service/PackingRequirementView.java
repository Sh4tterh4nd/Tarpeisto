package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.PackingRequirementType;
import java.math.BigDecimal;
import java.util.UUID;

public record PackingRequirementView(
        UUID id,
        PackingRequirementType type,
        UUID assetModelId,
        UUID specificAssetId,
        BigDecimal requiredQuantity,
        int displayOrder,
        boolean archived,
        long version) {}
