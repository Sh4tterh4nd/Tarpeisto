package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.AssetModelStockSummaryView;
import java.math.BigDecimal;
import java.util.UUID;

/** Response for the calculated (never stored) low-stock status of a model (specification section 6.4). */
public record AssetModelStockSummaryResponse(
        UUID assetModelId,
        String assetModelName,
        String stockUnitLabel,
        BigDecimal totalQuantity,
        BigDecimal lowStockThreshold,
        boolean lowStock) {

    public static AssetModelStockSummaryResponse from(AssetModelStockSummaryView view) {
        return new AssetModelStockSummaryResponse(
                view.assetModelId(),
                view.assetModelName(),
                view.stockUnitLabel(),
                view.totalQuantity(),
                view.lowStockThreshold(),
                view.lowStock());
    }
}
