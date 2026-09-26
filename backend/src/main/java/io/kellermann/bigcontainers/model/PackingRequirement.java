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
import java.util.UUID;

@Entity
@Table(name = "packing_requirement")
public class PackingRequirement {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "container_asset_id")
    private UUID containerAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type")
    private PackingRequirementType requirementType;

    @Column(name = "asset_model_id")
    private UUID assetModelId;

    @Column(name = "specific_asset_id")
    private UUID specificAssetId;

    @Column(name = "required_quantity")
    private BigDecimal requiredQuantity;

    @Column(name = "display_order")
    private int displayOrder;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected PackingRequirement() {}

    public PackingRequirement(
            UUID id,
            UUID org,
            UUID container,
            PackingRequirementType type,
            UUID model,
            UUID asset,
            BigDecimal quantity,
            int order,
            Instant now) {
        this.id = id;
        organizationId = org;
        containerAssetId = container;
        requirementType = type;
        assetModelId = model;
        specificAssetId = asset;
        requiredQuantity = quantity;
        displayOrder = order;
        createdAt = now;
        updatedAt = now;
    }

    public void archive(Instant now) {
        archivedAt = now;
        updatedAt = now;
    }

    public void restore(Instant now) {
        archivedAt = null;
        updatedAt = now;
    }

    public void change(PackingRequirementType type, UUID model, UUID asset, BigDecimal quantity, Instant now) {
        requirementType = type;
        assetModelId = model;
        specificAssetId = asset;
        requiredQuantity = quantity;
        updatedAt = now;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getContainerAssetId() {
        return containerAssetId;
    }

    public PackingRequirementType getRequirementType() {
        return requirementType;
    }

    public UUID getAssetModelId() {
        return assetModelId;
    }

    public UUID getSpecificAssetId() {
        return specificAssetId;
    }

    public BigDecimal getRequiredQuantity() {
        return requiredQuantity;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public long getVersion() {
        return version;
    }
}
