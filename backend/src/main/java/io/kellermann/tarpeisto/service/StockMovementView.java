package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.StockMovement;
import io.kellermann.tarpeisto.model.StockMovementReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Read projection of one immutable {@code StockMovement} ledger entry (specification section 6.4). */
public record StockMovementView(
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

    public static StockMovementView from(StockMovement movement) {
        return new StockMovementView(
                movement.getId(),
                movement.getConsumableStockBalanceId(),
                movement.getQuantityDelta(),
                movement.getResultingQuantity(),
                movement.getStockUnitLabel(),
                movement.getReason(),
                movement.getActorUserId(),
                movement.getNote(),
                movement.getTransferGroupId(),
                movement.getEventReferenceId(),
                movement.getAuditReferenceId(),
                movement.getOccurredAt());
    }
}
