package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared catalog definition for either individually identified equipment or quantity-tracked
 * consumable stock (specification section 6). Never named {@code Model} - see
 * docs/DEVELOPMENT_POLICIES.md section 4.1.
 *
 * <p>Model-unit-specific values ({@code Condition}, purchase date, serial number, MAC address, ...)
 * deliberately do not live here (specification section 6.1): those belong to Phase 2b's {@code
 * physical_asset}, out of scope for this change.
 *
 * <p>The cross-field invariants below (specification section 6.2: a {@link TrackingMode#QUANTITY_STOCK}
 * model "cannot be container-capable", and the stock-unit-label/low-stock-threshold fields only
 * apply to a quantity-tracked model) are enforced here in the constructor and every mutator, and
 * are backed a second time by database {@code CHECK} constraints in {@code V4__create_catalog_schema.sql},
 * per docs/DEVELOPMENT_POLICIES.md section 5.2 ("Important invariants are backed by PostgreSQL
 * constraints as well as service validation").
 */
@Entity
@Table(name = "asset_model")
public class AssetModel {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "replacement_url")
    private String replacementUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "tracking_mode", nullable = false, length = 20)
    private TrackingMode trackingMode;

    @Column(name = "stock_unit_label", length = 50)
    private String stockUnitLabel;

    @Column(name = "low_stock_threshold", precision = 12, scale = 3)
    private BigDecimal lowStockThreshold;

    @Column(name = "can_contain_assets", nullable = false)
    private boolean canContainAssets;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AssetModel() {
        // JPA
    }

    public AssetModel(
            UUID id,
            UUID organizationId,
            String name,
            String description,
            UUID categoryId,
            String replacementUrl,
            TrackingMode trackingMode,
            String stockUnitLabel,
            BigDecimal lowStockThreshold,
            boolean canContainAssets,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.name = requireNonBlankName(name);
        this.description = blankToNull(description);
        this.categoryId = categoryId;
        this.replacementUrl = blankToNull(replacementUrl);
        validateTrackingConsistency(trackingMode, stockUnitLabel, lowStockThreshold, canContainAssets);
        this.trackingMode = trackingMode;
        this.stockUnitLabel = blankToNull(stockUnitLabel);
        this.lowStockThreshold = lowStockThreshold;
        this.canContainAssets = canContainAssets;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void rename(String newName, String newDescription, Instant now) {
        this.name = requireNonBlankName(newName);
        this.description = blankToNull(newDescription);
        touch(now);
    }

    public void changeCategory(UUID newCategoryId, Instant now) {
        this.categoryId = newCategoryId;
        touch(now);
    }

    public void changeReplacementUrl(String newReplacementUrl, Instant now) {
        this.replacementUrl = blankToNull(newReplacementUrl);
        touch(now);
    }

    /**
     * Changes the tracking mode together with the fields whose applicability depends on it
     * (specification section 6.1/6.2). Callers (see {@code AssetModelService}) are responsible for
     * the separate rule that changing tracking mode is prohibited once dependent data exists; that
     * guard depends on tables Phase 2b has not created yet and therefore cannot live here.
     */
    public void changeTrackingMode(
            TrackingMode newTrackingMode, String newStockUnitLabel, BigDecimal newLowStockThreshold, Instant now) {
        boolean canContainAssetsAfterChange = newTrackingMode == TrackingMode.QUANTITY_STOCK ? false : canContainAssets;
        validateTrackingConsistency(
                newTrackingMode, newStockUnitLabel, newLowStockThreshold, canContainAssetsAfterChange);
        this.trackingMode = newTrackingMode;
        this.stockUnitLabel = blankToNull(newStockUnitLabel);
        this.lowStockThreshold = newLowStockThreshold;
        this.canContainAssets = canContainAssetsAfterChange;
        touch(now);
    }

    /**
     * Enables or disables container capability (specification section 6.3). Rejects enabling it on
     * a quantity-tracked model. Disabling it while units currently contain assets or have active
     * packing requirements is also prohibited by the specification, but neither table exists yet
     * (Phase 2b); {@code AssetModelService} is the seam where that check will be added.
     */
    public void setCanContainAssets(boolean newCanContainAssets, Instant now) {
        validateTrackingConsistency(trackingMode, stockUnitLabel, lowStockThreshold, newCanContainAssets);
        this.canContainAssets = newCanContainAssets;
        touch(now);
    }

    public void archive(Instant now) {
        this.archivedAt = Objects.requireNonNull(now, "now must not be null");
        touch(now);
    }

    public void restore(Instant now) {
        this.archivedAt = null;
        touch(now);
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public boolean isQuantityTracked() {
        return trackingMode == TrackingMode.QUANTITY_STOCK;
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Mirrors the {@code asset_model} table's {@code CHECK} constraints (specification section
     * 6.1/6.2): a quantity-tracked model cannot be container-capable, must carry a stock unit
     * label, and may carry a non-negative low-stock threshold; a serialized model carries neither.
     */
    private static void validateTrackingConsistency(
            TrackingMode trackingMode, String stockUnitLabel, BigDecimal lowStockThreshold, boolean canContainAssets) {
        Objects.requireNonNull(trackingMode, "trackingMode must not be null");
        if (trackingMode == TrackingMode.QUANTITY_STOCK) {
            if (canContainAssets) {
                throw new IllegalArgumentException("A QUANTITY_STOCK asset model cannot be container-capable.");
            }
            if (stockUnitLabel == null || stockUnitLabel.isBlank()) {
                throw new IllegalArgumentException("A QUANTITY_STOCK asset model requires a stock unit label.");
            }
            if (lowStockThreshold != null && lowStockThreshold.signum() < 0) {
                throw new IllegalArgumentException("lowStockThreshold must not be negative.");
            }
        } else {
            if (stockUnitLabel != null && !stockUnitLabel.isBlank()) {
                throw new IllegalArgumentException("A SERIALIZED_ASSET model must not carry a stock unit label.");
            }
            if (lowStockThreshold != null) {
                throw new IllegalArgumentException("A SERIALIZED_ASSET model must not carry a low-stock threshold.");
            }
        }
    }

    private static String requireNonBlankName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCategoryId() {
        return categoryId;
    }

    public String getReplacementUrl() {
        return replacementUrl;
    }

    public TrackingMode getTrackingMode() {
        return trackingMode;
    }

    public String getStockUnitLabel() {
        return stockUnitLabel;
    }

    public BigDecimal getLowStockThreshold() {
        return lowStockThreshold;
    }

    public boolean isCanContainAssets() {
        return canContainAssets;
    }

    public Instant getArchivedAt() {
        return archivedAt;
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
        if (!(other instanceof AssetModel that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
