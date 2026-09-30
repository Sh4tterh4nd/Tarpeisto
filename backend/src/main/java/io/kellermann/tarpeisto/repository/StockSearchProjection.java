package io.kellermann.tarpeisto.repository;

import java.math.BigDecimal;
import java.util.UUID;

public record StockSearchProjection(
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
        long version,
        String sortAnchor,
        UUID cursorId) {}
