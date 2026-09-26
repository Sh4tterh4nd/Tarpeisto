package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.TrackingMode;
import io.kellermann.bigcontainers.service.AssetModelView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response element for the asset model catalog endpoints. */
public record AssetModelResponse(
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

    public static AssetModelResponse from(AssetModelView view) {
        return new AssetModelResponse(
                view.id(),
                view.name(),
                view.description(),
                view.categoryId(),
                view.replacementUrl(),
                view.trackingMode(),
                view.stockUnitLabel(),
                view.lowStockThreshold(),
                view.canContainAssets(),
                view.archived(),
                view.createdAt(),
                view.updatedAt());
    }
}
