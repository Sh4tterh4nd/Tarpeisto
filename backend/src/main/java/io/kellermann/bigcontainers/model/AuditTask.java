package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_task")
public class AuditTask {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "audit_batch_id")
    private UUID auditBatchId;

    @Column(name = "container_asset_id")
    private UUID containerAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private AuditTaskState state;

    @Column(name = "created_at")
    private Instant createdAt;

    protected AuditTask() {}

    public AuditTask(UUID id, UUID org, UUID batch, UUID container, AuditTaskState state, Instant at) {
        this.id = id;
        organizationId = org;
        auditBatchId = batch;
        containerAssetId = container;
        this.state = state;
        createdAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAuditBatchId() {
        return auditBatchId;
    }

    public UUID getContainerAssetId() {
        return containerAssetId;
    }

    public AuditTaskState getState() {
        return state;
    }

    public void markReady() {
        if (state == AuditTaskState.BLOCKED) state = AuditTaskState.READY;
    }

    public void complete() {
        if (state != AuditTaskState.READY) throw new IllegalStateException("Only a ready audit task can complete.");
        state = AuditTaskState.COMPLETED;
    }

    public void reopen() {
        state = AuditTaskState.READY;
    }

    public void block() {
        state = AuditTaskState.BLOCKED;
    }
}
