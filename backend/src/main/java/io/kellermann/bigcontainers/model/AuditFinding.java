package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_finding")
public class AuditFinding {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "container_audit_id")
    private UUID auditId;

    @Column(name = "physical_asset_id")
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type")
    private AuditFindingType type;

    @Column(name = "note")
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private String detail;

    @Column(name = "recorded_by_user_id")
    private UUID actor;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    protected AuditFinding() {}

    public AuditFinding(
            UUID id,
            UUID org,
            UUID audit,
            UUID asset,
            AuditFindingType type,
            String note,
            String detail,
            UUID actor,
            Instant at) {
        this.id = id;
        organizationId = org;
        auditId = audit;
        assetId = asset;
        this.type = type;
        this.note = note;
        this.detail = detail;
        this.actor = actor;
        recordedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public AuditFindingType getType() {
        return type;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public String getNote() {
        return note;
    }

    public String getDetail() {
        return detail;
    }
}
