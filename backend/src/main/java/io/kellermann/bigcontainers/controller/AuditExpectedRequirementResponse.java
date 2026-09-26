package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.AuditExpectedRequirementView;
import java.math.BigDecimal;
import java.util.UUID;

public record AuditExpectedRequirementResponse(
        UUID id,
        String type,
        UUID assetModelId,
        UUID specificAssetId,
        BigDecimal requiredQuantity,
        int displayOrder,
        String snapshot,
        boolean satisfied) {
    static AuditExpectedRequirementResponse from(AuditExpectedRequirementView v) {
        return new AuditExpectedRequirementResponse(
                v.id(),
                v.type(),
                v.assetModelId(),
                v.specificAssetId(),
                v.requiredQuantity(),
                v.displayOrder(),
                v.snapshot(),
                v.satisfied());
    }
}
