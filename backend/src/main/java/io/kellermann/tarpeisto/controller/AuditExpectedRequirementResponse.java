package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AuditExpectedRequirementView;
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
        boolean satisfied,
        BigDecimal matchedQuantity) {
    static AuditExpectedRequirementResponse from(AuditExpectedRequirementView v) {
        return new AuditExpectedRequirementResponse(
                v.id(),
                v.type(),
                v.assetModelId(),
                v.specificAssetId(),
                v.requiredQuantity(),
                v.displayOrder(),
                v.snapshot(),
                v.satisfied(),
                v.matchedQuantity());
    }
}
