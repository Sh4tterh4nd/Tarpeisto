package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Final immutable decision, intentionally separate from {@link AuditFinding}. */
@Entity
@Table(name = "audit_finding_resolution")
public class FindingResolution {
    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "audit_finding_id", updatable = false)
    private UUID findingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", updatable = false)
    private FindingResolutionAction action;

    @Column(name = "operation_id", updatable = false)
    private UUID operationId;

    @Column(name = "fingerprint", updatable = false)
    private String fingerprint;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "target_asset_id", updatable = false)
    private UUID targetAssetId;

    @Column(name = "target_container_asset_id", updatable = false)
    private UUID targetContainerAssetId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "resolved_at", updatable = false)
    private Instant resolvedAt;

    protected FindingResolution() {}

    public FindingResolution(
            UUID id,
            UUID organizationId,
            UUID findingId,
            FindingResolutionAction action,
            UUID operationId,
            String fingerprint,
            String note,
            UUID targetAssetId,
            UUID targetContainerAssetId,
            UUID actorUserId,
            Instant resolvedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.findingId = findingId;
        this.action = action;
        this.operationId = operationId;
        this.fingerprint = fingerprint;
        this.note = note;
        this.targetAssetId = targetAssetId;
        this.targetContainerAssetId = targetContainerAssetId;
        this.actorUserId = actorUserId;
        this.resolvedAt = resolvedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFindingId() {
        return findingId;
    }

    public FindingResolutionAction getAction() {
        return action;
    }

    public UUID getOperationId() {
        return operationId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getNote() {
        return note;
    }

    public UUID getTargetAssetId() {
        return targetAssetId;
    }

    public UUID getTargetContainerAssetId() {
        return targetContainerAssetId;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
