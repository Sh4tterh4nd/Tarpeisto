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
@Table(name = "container_audit")
public class ContainerAudit {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "audit_batch_id")
    private UUID auditBatchId;

    @Column(name = "audit_task_id")
    private UUID auditTaskId;

    @Column(name = "container_asset_id")
    private UUID containerAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state")
    private ContainerAuditState state;

    @Column(name = "started_by_user_id")
    private UUID startedByUserId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_by_user_id")
    private UUID completedByUserId;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "completion_outcome")
    private AuditCompletionOutcome completionOutcome;

    @Column(name = "final_container_code")
    private String finalContainerCode;

    @Column(name = "seal_confirmed")
    private Boolean sealConfirmed;

    protected ContainerAudit() {}

    public ContainerAudit(UUID id, UUID org, UUID batch, UUID task, UUID container, UUID actor, Instant at) {
        this.id = id;
        organizationId = org;
        auditBatchId = batch;
        auditTaskId = task;
        containerAssetId = container;
        startedByUserId = actor;
        startedAt = at;
        state = ContainerAuditState.IN_PROGRESS;
    }

    public void complete(UUID actor, AuditCompletionOutcome outcome, String code, Boolean seal, Instant at) {
        if (state == ContainerAuditState.IN_PROGRESS) {
            state = ContainerAuditState.COMPLETED;
            completedByUserId = actor;
            completionOutcome = outcome;
            finalContainerCode = code;
            sealConfirmed = seal;
            completedAt = at;
        }
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

    public UUID getAuditTaskId() {
        return auditTaskId;
    }

    public UUID getContainerAssetId() {
        return containerAssetId;
    }

    public ContainerAuditState getState() {
        return state;
    }

    public AuditCompletionOutcome getCompletionOutcome() {
        return completionOutcome;
    }
}
