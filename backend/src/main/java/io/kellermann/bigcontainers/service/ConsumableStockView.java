package io.kellermann.bigcontainers.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a {@code ConsumableStock} balance (specification section 6.4). {@link
 * #containerAssetId} is currently always present - see {@code ConsumableStock}'s Javadoc for the
 * Phase 4 stock-place seam.
 */
public record ConsumableStockView(
        UUID id,
        UUID assetModelId,
        String assetModelName,
        String stockUnitLabel,
        UUID containerAssetId,
        String containerAssetDisplayName,
        BigDecimal quantity,
        Instant createdAt,
        Instant updatedAt) {}
