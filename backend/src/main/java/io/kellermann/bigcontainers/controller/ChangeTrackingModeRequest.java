package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.TrackingMode;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}/tracking-mode}. */
public record ChangeTrackingModeRequest(
        @NotNull TrackingMode trackingMode, String stockUnitLabel, BigDecimal lowStockThreshold) {}
