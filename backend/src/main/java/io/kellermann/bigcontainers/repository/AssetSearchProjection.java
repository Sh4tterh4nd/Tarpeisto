package io.kellermann.bigcontainers.repository;

import io.kellermann.bigcontainers.model.Condition;
import io.kellermann.bigcontainers.model.LifecycleState;
import java.util.UUID;

/** Compact asset/catalog projection; no entities or lazy relationships are loaded. */
public record AssetSearchProjection(
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
