package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The current amount of one {@link TrackingMode#QUANTITY_STOCK} {@link AssetModel} at exactly one
 * stock place (specification section 6.4). Never named {@code ConsumableStockBalance} in Java -
 * see docs/DEVELOPMENT_POLICIES.md section 4.1 ("Use {@code ConsumableStock} for the current
 * amount ... at one location or container").
 *
 * <p><strong>Stock-place ordering conflict and its resolution</strong> (see
 * {@code V6__create_consumable_stock_schema.sql}'s header comment for the full reasoning and exact
 * Phase 4 impact): specification section 6.4 says a balance belongs to "exactly one stock place:
 * either a direct location or a container asset," but the {@code location} table does not exist
 * until Phase 4. This entity therefore only maps {@link #containerAssetId} - the stock-place kind
 * that exists today. The underlying table already carries a {@code location_id} column reserved
 * for Phase 4, constrained to always be {@code NULL} for now; it is deliberately left unmapped here
 * so nothing in this codebase can set it before the {@code location} table and its validation
 * exist. Phase 4 adds the mapping (and very likely widens this into a sealed {@code StockPlace}
 * choice) without any data migration, since every existing row already satisfies the wider
 * constraint the migration documents.
 *
 * <p><strong>Concurrency:</strong> every quantity mutation goes through {@code
 * ConsumableStockLedgerRepository}'s raw, atomic SQL (an {@code UPDATE ... WHERE quantity + :delta
 * >= 0 RETURNING quantity} for a single balance, or two canonically ordered {@code SELECT ... FOR
 * UPDATE} statements for a transfer's two balances), never through this entity's own setters -
 * there are none beyond construction. {@code ConsumableStockService} deliberately never loads this
 * entity through {@code ConsumableStockRepository} (JPA) in the same transaction as one of those raw
 * mutations, so Hibernate's first-level cache can never serve a stale, pre-mutation quantity back
 * out. Reads (list/get endpoints) use {@code ConsumableStockRepository} in their own read-only
 * transaction instead.
 */
@Entity
@Table(name = "consumable_stock_balance")
public class ConsumableStock {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_model_id", nullable = false, updatable = false)
    private UUID assetModelId;

    @Column(name = "container_asset_id", updatable = false)
    private UUID containerAssetId;

    @Column(name = "location_id", updatable = false)
    private UUID locationId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ConsumableStock() {
        // JPA
    }

    /**
     * Constructs the read-side projection of a balance row created by {@code
     * ConsumableStockLedgerRepository}'s raw SQL. Not used to persist a new balance - the ledger
     * repository's {@code INSERT ... ON CONFLICT DO NOTHING} owns row creation so the "ensure
     * exists" step and the first atomic quantity mutation are one race-free unit of work.
     */
    public ConsumableStock(
            UUID id,
            UUID organizationId,
            UUID assetModelId,
            UUID containerAssetId,
            BigDecimal quantity,
            Instant createdAt,
            Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.assetModelId = Objects.requireNonNull(assetModelId, "assetModelId must not be null");
        this.containerAssetId = Objects.requireNonNull(containerAssetId, "containerAssetId must not be null");
        this.quantity = requireNonNegative(quantity);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static BigDecimal requireNonNegative(BigDecimal quantity) {
        Objects.requireNonNull(quantity, "quantity must not be null");
        if (quantity.signum() < 0) {
            throw new IllegalArgumentException("quantity must not be negative: " + quantity);
        }
        return quantity;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAssetModelId() {
        return assetModelId;
    }

    public UUID getContainerAssetId() {
        return containerAssetId;
    }

    public UUID getLocationId() {
        return locationId;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ConsumableStock that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
