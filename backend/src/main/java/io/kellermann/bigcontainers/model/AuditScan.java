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
@Table(name = "audit_scan")
public class AuditScan {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "container_audit_id")
    private UUID auditId;

    @Column(name = "audit_batch_id")
    private UUID auditBatchId;

    @Column(name = "physical_asset_id")
    private UUID assetId;

    @Column(name = "client_operation_id")
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome")
    private AuditScanOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_snapshot", columnDefinition = "jsonb")
    private String contextSnapshot;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "scanned_by_user_id")
    private UUID scannedBy;

    @Column(name = "undone_at")
    private Instant undoneAt;

    @Column(name = "undone_by_user_id")
    private UUID undoneBy;

    protected AuditScan() {}

    public AuditScan(
            UUID id,
            UUID org,
            UUID audit,
            UUID batch,
            UUID asset,
            UUID op,
            AuditScanOutcome outcome,
            UUID actor,
            Instant at,
            String contextSnapshot) {
        this.id = id;
        organizationId = org;
        auditId = audit;
        auditBatchId = batch;
        assetId = asset;
        operationId = op;
        this.outcome = outcome;
        this.contextSnapshot = contextSnapshot;
        scannedBy = actor;
        scannedAt = at;
    }

    public void undo(UUID actor, Instant at) {
        if (undoneAt == null) {
            undoneAt = at;
            undoneBy = actor;
        }
    }

    public void matchAs(AuditScanOutcome outcome) {
        this.outcome = outcome;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public String getContextSnapshot() {
        return contextSnapshot;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getAuditId() {
        return auditId;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public AuditScanOutcome getOutcome() {
        return outcome;
    }

    public Instant getUndoneAt() {
        return undoneAt;
    }
}
