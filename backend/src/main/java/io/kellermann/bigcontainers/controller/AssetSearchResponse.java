package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.model.LifecycleState;
import io.kellermann.bigcontainers.service.AssetSearchView;
import java.util.UUID;

/** Bounded list element for {@code GET /api/v1/assets}. */
public record AssetSearchResponse(
        UUID id,
        String displayName,
        String publicCode,
        UUID assetModelId,
        String assetModelName,
        UUID categoryId,
        String categoryName,
        String categoryColor,
        boolean canContainAssets,
        Condition condition,
        LifecycleState lifecycleState,
        boolean archived,
        UUID parentContainerAssetId,
        long placementVersion) {
    public static AssetSearchResponse from(AssetSearchView view) {
        return new AssetSearchResponse(
                view.id(),
                view.displayName(),
                view.publicCode(),
                view.assetModelId(),
                view.assetModelName(),
                view.categoryId(),
                view.categoryName(),
                view.categoryColor(),
                view.canContainAssets(),
                view.condition(),
                view.lifecycleState(),
                view.archived(),
                view.parentContainerAssetId(),
                view.placementVersion());
    }
}
