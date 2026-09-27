package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_expected_requirement")
public class AuditExpectedRequirement {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "container_audit_id")
    private UUID auditId;

    @Column(name = "source_packing_requirement_id")
    private UUID sourceRequirementId;

    @Enumerated(EnumType.STRING)
    @Column(name = "requirement_type")
    private PackingRequirementType type;

    @Column(name = "asset_model_id")
    private UUID assetModelId;

    @Column(name = "specific_asset_id")
    private UUID specificAssetId;

    @Column(name = "required_quantity")
    private BigDecimal requiredQuantity;

    @Column(name = "display_order")
    private int displayOrder;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "snapshot", columnDefinition = "jsonb")
    private String snapshot;

    protected AuditExpectedRequirement() {}

    public AuditExpectedRequirement(UUID id, UUID org, UUID audit, PackingRequirement p, String snapshot) {
        this.id = id;
        organizationId = org;
        auditId = audit;
        sourceRequirementId = p.getId();
        type = p.getRequirementType();
        assetModelId = p.getAssetModelId();
        specificAssetId = p.getSpecificAssetId();
        requiredQuantity = p.getRequiredQuantity();
        displayOrder = p.getDisplayOrder();
        this.snapshot = snapshot;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public PackingRequirementType getType() {
        return type;
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

    public String getSnapshot() {
        return snapshot;
    }
}
