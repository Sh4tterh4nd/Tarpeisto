package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.PackingRequirementType;
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
