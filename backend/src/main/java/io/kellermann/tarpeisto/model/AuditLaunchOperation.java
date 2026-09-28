package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Durable idempotency record for starting a standalone container audit graph. */
@Entity
@Table(name = "audit_launch_operation")
public class AuditLaunchOperation {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "client_operation_id")
    private UUID operationId;

    @Column(name = "fingerprint")
    private String fingerprint;

    @Column(name = "audit_task_id")
    private UUID taskId;

    @Column(name = "recorded_at")
    private Instant recordedAt;

    protected AuditLaunchOperation() {}

    public AuditLaunchOperation(
            UUID id, UUID organizationId, UUID operationId, String fingerprint, UUID taskId, Instant recordedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.operationId = operationId;
        this.fingerprint = fingerprint;
        this.taskId = taskId;
        this.recordedAt = recordedAt;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public UUID getTaskId() {
        return taskId;
    }
}
