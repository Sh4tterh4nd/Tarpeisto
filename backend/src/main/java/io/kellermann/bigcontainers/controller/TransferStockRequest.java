package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/consumable-stock/transfer}. */
public record TransferStockRequest(
        @NotNull UUID sourceContainerAssetId,
        @NotNull UUID destinationContainerAssetId,
        @NotNull BigDecimal quantity,
        String note) {}
