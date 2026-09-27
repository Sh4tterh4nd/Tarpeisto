package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable, append-only change to a {@link ConsumableStock} balance (specification section
 * 6.4). Rows are never updated or deleted by application code - there are deliberately no setters
 * here beyond the constructor, mirroring {@link ActivityLog}/{@link AssetStateChange} - and the
 * {@code stock_movement} table additionally rejects any {@code UPDATE}/{@code DELETE} at the
 * database level (see {@code V6__create_consumable_stock_schema.sql}), because the ledger's
 * append-only property is exactly what the balance-reconciliation guarantee (a balance's quantity
 * always equals the sum of its movements' deltas) depends on.
 *
 * <p>{@link #eventReferenceId} and {@link #auditReferenceId} are optional references to records
 * that do not exist yet (events/bookings are Phase 7, audits are Phase 9); the underlying columns
 * are plain, unconstrained {@code UUID}s with no foreign key for exactly that reason - see the
 * migration's header comment for the documented seam.
 */
@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "consumable_stock_balance_id", nullable = false, updatable = false)
    private UUID consumableStockBalanceId;

    @Column(name = "quantity_delta", nullable = false, updatable = false, precision = 14, scale = 3)
    private BigDecimal quantityDelta;

    @Column(name = "resulting_quantity", nullable = false, updatable = false, precision = 14, scale = 3)
    private BigDecimal resultingQuantity;

    @Column(name = "stock_unit_label", nullable = false, updatable = false, length = 50)
    private String stockUnitLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private StockMovementReason reason;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "note", updatable = false, columnDefinition = "text")
    private String note;

    @Column(name = "transfer_group_id", updatable = false)
    private UUID transferGroupId;

    @Column(name = "event_reference_id", updatable = false)
    private UUID eventReferenceId;

    @Column(name = "audit_reference_id", updatable = false)
    private UUID auditReferenceId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected StockMovement() {
        // JPA
    }

    public StockMovement(
            UUID id,
            UUID organizationId,
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
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.consumableStockBalanceId =
                Objects.requireNonNull(consumableStockBalanceId, "consumableStockBalanceId must not be null");
        this.quantityDelta = requireNonZero(quantityDelta);
        this.resultingQuantity = requireNonNegative(resultingQuantity);
        this.stockUnitLabel = requireNonBlank(stockUnitLabel);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.actorUserId = actorUserId;
        this.note = note == null || note.isBlank() ? null : note.trim();
        this.transferGroupId = requireTransferGroupMatchesReason(reason, transferGroupId);
        this.eventReferenceId = eventReferenceId;
        this.auditReferenceId = auditReferenceId;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static BigDecimal requireNonZero(BigDecimal quantityDelta) {
        Objects.requireNonNull(quantityDelta, "quantityDelta must not be null");
        if (quantityDelta.signum() == 0) {
            throw new IllegalArgumentException("quantityDelta must not be zero");
        }
        return quantityDelta;
    }

    private static BigDecimal requireNonNegative(BigDecimal resultingQuantity) {
        Objects.requireNonNull(resultingQuantity, "resultingQuantity must not be null");
        if (resultingQuantity.signum() < 0) {
            throw new IllegalArgumentException("resultingQuantity must not be negative: " + resultingQuantity);
        }
        return resultingQuantity;
    }

    private static String requireNonBlank(String stockUnitLabel) {
        if (stockUnitLabel == null || stockUnitLabel.isBlank()) {
            throw new IllegalArgumentException("stockUnitLabel must not be blank");
        }
        return stockUnitLabel;
    }

    private static UUID requireTransferGroupMatchesReason(StockMovementReason reason, UUID transferGroupId) {
        boolean isTransfer = reason == StockMovementReason.TRANSFER;
        if (isTransfer && transferGroupId == null) {
            throw new IllegalArgumentException("A TRANSFER movement requires a transferGroupId");
        }
        if (!isTransfer && transferGroupId != null) {
            throw new IllegalArgumentException("Only a TRANSFER movement may carry a transferGroupId");
        }
        return transferGroupId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getConsumableStockBalanceId() {
        return consumableStockBalanceId;
    }

    public BigDecimal getQuantityDelta() {
        return quantityDelta;
    }

    public BigDecimal getResultingQuantity() {
        return resultingQuantity;
    }

    public String getStockUnitLabel() {
        return stockUnitLabel;
    }

    public StockMovementReason getReason() {
        return reason;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getNote() {
        return note;
    }

    public UUID getTransferGroupId() {
        return transferGroupId;
    }

    public UUID getEventReferenceId() {
        return eventReferenceId;
    }

    public UUID getAuditReferenceId() {
        return auditReferenceId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StockMovement that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
