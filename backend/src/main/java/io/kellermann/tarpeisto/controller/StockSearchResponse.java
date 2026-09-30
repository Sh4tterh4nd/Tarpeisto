package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.CatalogSearchService;
import java.math.BigDecimal;
import java.util.UUID;

public record StockSearchResponse(
        UUID id,
        UUID assetModelId,
        String assetModelName,
        UUID categoryId,
        String categoryName,
        String stockUnitLabel,
        UUID containerAssetId,
        UUID locationId,
        String placePath,
        BigDecimal quantity,
        BigDecimal totalOnHand,
        BigDecimal lowStockThreshold,
        boolean lowStock,
        boolean archived,
        long version) {
    static StockSearchResponse from(CatalogSearchService.StockView r) {
        return new StockSearchResponse(
                r.id(),
                r.assetModelId(),
                r.assetModelName(),
                r.categoryId(),
                r.categoryName(),
                r.stockUnitLabel(),
                r.containerAssetId(),
                r.locationId(),
                r.placePath(),
                r.quantity(),
                r.totalOnHand(),
                r.lowStockThreshold(),
                r.lowStock(),
                r.archived(),
                r.version());
    }
}
