package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.TrackingMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for {@code POST /api/v1/asset-models}. */
public record CreateAssetModelRequest(
        @NotBlank String name,
        String description,
        UUID categoryId,
        String replacementUrl,
        @NotNull TrackingMode trackingMode,
        String stockUnitLabel,
        BigDecimal lowStockThreshold,
        boolean canContainAssets) {}
