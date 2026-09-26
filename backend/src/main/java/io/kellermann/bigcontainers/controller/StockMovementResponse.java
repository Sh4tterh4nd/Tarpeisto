package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.StockMovementReason;
import io.kellermann.bigcontainers.service.StockMovementView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Response for the immutable stock-movement ledger (specification section 6.4). */
public record StockMovementResponse(
        UUID id,
        UUID consumableStockBalanceId,
        BigDecimal quantityDelta,
        BigDecimal resultingQuantity,
        String stockUnitLabel,
        StockMovementReason reason,
        UUID actorUserId,
        String note,
        UUID transferGroupId,
        UUID eventReferenceId,
        UUID auditReferenceId,
        Instant occurredAt) {

    public static StockMovementResponse from(StockMovementView view) {
        return new StockMovementResponse(
                view.id(),
                view.consumableStockBalanceId(),
                view.quantityDelta(),
                view.resultingQuantity(),
                view.stockUnitLabel(),
                view.reason(),
                view.actorUserId(),
                view.note(),
                view.transferGroupId(),
                view.eventReferenceId(),
                view.auditReferenceId(),
                view.occurredAt());
    }
}
