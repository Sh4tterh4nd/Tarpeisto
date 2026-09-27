package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.PackingRequirementType;
import java.math.BigDecimal;

public record PackingRequirementRequest(
        PackingRequirementType type,
        java.util.UUID assetModelId,
        java.util.UUID specificAssetId,
        String specificAssetReference,
        BigDecimal requiredQuantity,
        Boolean assignToContainer,
        Long expectedAssetVersion) {
    public PackingRequirementRequest(
            PackingRequirementType type,
            java.util.UUID assetModelId,
            String specificAssetReference,
            BigDecimal requiredQuantity) {
        this(type, assetModelId, null, specificAssetReference, requiredQuantity, false, null);
    }

    /** Templates and edits may select an identity but must never silently perform placement. */
    public String referenceWithoutAssignment() {
        if (Boolean.TRUE.equals(assignToContainer)) {
            throw new ValidationFailedException(
                    "Immediate assignment is only available when adding a container requirement.");
        }
        if (specificAssetId != null && specificAssetReference != null && !specificAssetReference.isBlank()) {
            throw new ValidationFailedException("Select an exact asset by id or reference, not both.");
        }
        return specificAssetId == null ? specificAssetReference : specificAssetId.toString();
    }
}
