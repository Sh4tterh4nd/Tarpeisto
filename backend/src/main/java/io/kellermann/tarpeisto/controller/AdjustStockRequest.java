package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.StockMovementReason;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/asset-models/{assetModelId}/consumable-stock/adjust}.
 * {@code type} must be {@code MANUAL_ADJUSTMENT} or {@code AUDIT_ADJUSTMENT}; {@code reason} is the
 * explicit, non-blank justification specification section 6.4 requires for any Owner/Deputy
 * adjustment, distinct from {@code type}.
 */
public record AdjustStockRequest(
        UUID containerAssetId,
        UUID locationId,
        @NotNull BigDecimal delta,
        @NotNull StockMovementReason type,
        String reason,
        UUID auditReferenceId) {}
