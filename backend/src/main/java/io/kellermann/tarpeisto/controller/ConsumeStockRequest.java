package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/consumable-stock/consume}. */
public record ConsumeStockRequest(
        UUID containerAssetId, UUID locationId, @NotNull BigDecimal quantity, String note) {}
