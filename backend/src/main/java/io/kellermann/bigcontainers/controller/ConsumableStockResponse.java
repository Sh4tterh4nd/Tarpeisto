package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.ConsumableStockView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response for consumable stock balance endpoints (specification section 6.4). */
public record ConsumableStockResponse(
        UUID id,
        UUID assetModelId,
        String assetModelName,
        String stockUnitLabel,
        UUID containerAssetId,
        String containerAssetDisplayName,
        UUID locationId,
        String locationDisplayName,
        BigDecimal quantity,
        Instant createdAt,
        Instant updatedAt) {

    public static ConsumableStockResponse from(ConsumableStockView view) {
        return new ConsumableStockResponse(
                view.id(),
                view.assetModelId(),
                view.assetModelName(),
                view.stockUnitLabel(),
                view.containerAssetId(),
                view.containerAssetDisplayName(),
                view.locationId(),
                view.locationDisplayName(),
                view.quantity(),
                view.createdAt(),
                view.updatedAt());
    }
}
