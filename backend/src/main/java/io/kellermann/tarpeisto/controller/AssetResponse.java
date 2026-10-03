package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.Condition;
import io.kellermann.tarpeisto.model.LifecycleState;
import io.kellermann.tarpeisto.model.SealState;
import io.kellermann.tarpeisto.service.AssetView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response for the physical asset catalog endpoints (specification section 8). */
public record AssetResponse(
        UUID id,
        UUID assetModelId,
        String assetModelName,
        String publicCode,
        int unitNumber,
        String individualName,
        String displayName,
        Condition condition,
        LifecycleState lifecycleState,
        LocalDate purchaseDate,
        boolean archived,
        boolean metadataIncomplete,
        List<AssetCustomFieldValueResponse> values,
        Instant createdAt,
        Instant updatedAt,
        boolean sealable,
        SealState sealState,
        Instant sealVerifiedAt,
        Instant lastVerifiedAt,
        UUID lastVerifiedAuditId,
        UUID replacesAssetId,
        String containerColor,
        String unitDescription,
        long version) {

    public static AssetResponse from(AssetView view) {
        return new AssetResponse(
                view.id(),
                view.assetModelId(),
                view.assetModelName(),
                view.publicCode(),
                view.unitNumber(),
                view.individualName(),
                view.displayName(),
                view.condition(),
                view.lifecycleState(),
                view.purchaseDate(),
                view.archived(),
                view.metadataIncomplete(),
                view.values().stream().map(AssetCustomFieldValueResponse::from).toList(),
                view.createdAt(),
                view.updatedAt(),
                view.sealable(),
                view.sealState(),
                view.sealVerifiedAt(),
                view.lastVerifiedAt(),
                view.lastVerifiedAuditId(),
                view.replacesAssetId(),
                view.containerColor(),
                view.unitDescription(),
                view.version());
    }
}
