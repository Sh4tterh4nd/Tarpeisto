package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.PackingRequirementType;
import java.math.BigDecimal;

public record PackingRequirementRequest(
        PackingRequirementType type,
        java.util.UUID assetModelId,
        String specificAssetReference,
        BigDecimal requiredQuantity) {}
