package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.model.LifecycleState;
import java.util.UUID;

/** Bounded physical-asset result used by the Assets table and exact-requirement picker. */
public record AssetSearchView(
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
        long placementVersion) {}
