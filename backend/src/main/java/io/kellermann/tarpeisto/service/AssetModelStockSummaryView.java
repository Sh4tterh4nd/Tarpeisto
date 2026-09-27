package io.kellermann.tarpeisto.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A model's total on-hand consumable stock across every stock place in the organization, and
 * whether it is low (specification section 6.4: "Low-stock status is calculated from the model
 * threshold across the organization's active on-hand balances" - calculated here on every read,
 * never stored).
 */
public record AssetModelStockSummaryView(
        UUID assetModelId,
        String assetModelName,
        String stockUnitLabel,
        BigDecimal totalQuantity,
        BigDecimal lowStockThreshold,
        boolean lowStock) {}
