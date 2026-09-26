package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A model-defined unit field definition (specification section 7). Definitions are created on a
 * {@link TrackingMode#SERIALIZED_ASSET} {@link AssetModel} - a {@code QUANTITY_STOCK} model cannot
 * define these fields, enforced here, by {@code ModelCustomFieldService}, and by the
 * {@code tr_model_custom_field_reject_quantity_model} database trigger in
 * {@code V4__create_catalog_schema.sql}.
 *
 * <p>Values exist only on Phase 2b's {@code physical_asset} (out of scope here); this entity
 * carries only the definition.
 */
@Entity
@Table(name = "model_custom_field")
public class ModelCustomField {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_model_id", nullable = false, updatable = false)
    private UUID assetModelId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false, length = 20)
    private CustomFieldDataType dataType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ModelCustomField() {
        // JPA
    }

    public ModelCustomField(
            UUID id,
            UUID organizationId,
            UUID assetModelId,
            String name,
            CustomFieldDataType dataType,
            int displayOrder,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.assetModelId = Objects.requireNonNull(assetModelId, "assetModelId must not be null");
        this.name = requireNonBlankName(name);
        this.dataType = Objects.requireNonNull(dataType, "dataType must not be null");
        this.displayOrder = displayOrder;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void rename(String newName, Instant now) {
        this.name = requireNonBlankName(newName);
        touch(now);
    }

    /**
     * Changes the field's datatype (specification section 7.2: "Changing a field's datatype after
     * values exist is prohibited"). Phase 2b's {@code asset_custom_field_value} table does not
     * exist yet, so no value can exist yet either; {@code ModelCustomFieldService} is the seam
     * where that guard will be added once it does.
     */
    public void changeDataType(CustomFieldDataType newDataType, Instant now) {
        this.dataType = Objects.requireNonNull(newDataType, "newDataType must not be null");
        touch(now);
    }

    public void reorder(int newDisplayOrder, Instant now) {
        this.displayOrder = newDisplayOrder;
        touch(now);
    }

    /** Removing a field archives its definition and historical values rather than deleting it. */
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

    public boolean isDropdown() {
        return dataType == CustomFieldDataType.DROPDOWN;
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireNonBlankName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
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

    public String getName() {
        return name;
    }

    public CustomFieldDataType getDataType() {
        return dataType;
    }

    public int getDisplayOrder() {
        return displayOrder;
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
        if (!(other instanceof ModelCustomField that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
