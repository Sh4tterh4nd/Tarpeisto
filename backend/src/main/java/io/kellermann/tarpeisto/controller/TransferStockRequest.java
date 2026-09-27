package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/consumable-stock/transfer}. */
public record TransferStockRequest(
        UUID sourceContainerAssetId,
        UUID sourceLocationId,
        UUID destinationContainerAssetId,
        UUID destinationLocationId,
        @NotNull BigDecimal quantity,
        String note) {}
