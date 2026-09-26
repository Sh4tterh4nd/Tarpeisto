package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.AssetModel;
import io.kellermann.bigcontainers.model.TrackingMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read projection of an {@link AssetModel} for the catalog API. */
public record AssetModelView(
        UUID id,
        String name,
        String description,
        UUID categoryId,
        String replacementUrl,
        TrackingMode trackingMode,
        String stockUnitLabel,
        BigDecimal lowStockThreshold,
        boolean canContainAssets,
        boolean archived,
        Instant createdAt,
        Instant updatedAt) {

    public static AssetModelView from(AssetModel assetModel) {
        return new AssetModelView(
                assetModel.getId(),
                assetModel.getName(),
                assetModel.getDescription(),
                assetModel.getCategoryId(),
                assetModel.getReplacementUrl(),
                assetModel.getTrackingMode(),
                assetModel.getStockUnitLabel(),
                assetModel.getLowStockThreshold(),
                assetModel.isCanContainAssets(),
                assetModel.isArchived(),
                assetModel.getCreatedAt(),
                assetModel.getUpdatedAt());
    }
}
