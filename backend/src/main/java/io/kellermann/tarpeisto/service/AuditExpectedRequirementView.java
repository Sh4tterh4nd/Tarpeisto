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
        boolean satisfied,
        BigDecimal matchedQuantity) {
    public AuditExpectedRequirementView(
            UUID id,
            String type,
            UUID assetModelId,
            UUID specificAssetId,
            BigDecimal requiredQuantity,
            int displayOrder,
            String snapshot,
            boolean satisfied) {
        this(id, type, assetModelId, specificAssetId, requiredQuantity, displayOrder, snapshot, satisfied, null);
    }
}
