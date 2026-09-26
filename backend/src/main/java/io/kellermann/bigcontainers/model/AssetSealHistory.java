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
@Table(name = "asset_seal_history")
public class AssetSealHistory {
    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "asset_id", updatable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", updatable = false)
    private SealHistoryAction action;

    @Column(name = "source_audit_id", updatable = false)
    private UUID sourceAuditId;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "occurred_at", updatable = false)
    private Instant occurredAt;

    protected AssetSealHistory() {}

    public AssetSealHistory(
            UUID id,
            UUID organizationId,
            UUID assetId,
            SealHistoryAction action,
            UUID sourceAuditId,
            String note,
            UUID actorUserId,
            Instant occurredAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.assetId = assetId;
        this.action = action;
        this.sourceAuditId = sourceAuditId;
        this.note = note;
        this.actorUserId = actorUserId;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public SealHistoryAction getAction() {
        return action;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
