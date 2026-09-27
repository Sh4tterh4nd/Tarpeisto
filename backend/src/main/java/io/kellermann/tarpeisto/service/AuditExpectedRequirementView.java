package io.kellermann.tarpeisto.service;

import java.math.BigDecimal;
import java.util.UUID;

public record AuditExpectedRequirementView(
        UUID id,
        String type,
        UUID assetModelId,
        UUID specificAssetId,
        BigDecimal requiredQuantity,
        int displayOrder,
        String snapshot,
        boolean satisfied) {}
