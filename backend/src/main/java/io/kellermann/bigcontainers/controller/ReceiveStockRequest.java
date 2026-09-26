package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/consumable-stock/receive}. */
public record ReceiveStockRequest(
        @NotNull UUID containerAssetId, @NotNull BigDecimal quantity, String note) {}
