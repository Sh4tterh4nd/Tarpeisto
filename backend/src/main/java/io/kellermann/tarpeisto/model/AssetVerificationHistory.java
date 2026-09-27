package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable evidence that a physical asset was observed in a completed audit attempt. */
@Entity
@Table(name = "asset_verification_history")
public class AssetVerificationHistory {
    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "asset_id", updatable = false)
    private UUID assetId;

    @Column(name = "container_audit_id", updatable = false)
    private UUID auditId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", updatable = false)
    private VerificationState state;

    @Column(name = "verified_at", updatable = false)
    private Instant verifiedAt;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    protected AssetVerificationHistory() {}

    public AssetVerificationHistory(
            UUID id,
            UUID organizationId,
            UUID assetId,
            UUID auditId,
            VerificationState state,
            Instant verifiedAt,
            UUID actorUserId) {
        this.id = id;
        this.organizationId = organizationId;
        this.assetId = assetId;
        this.auditId = auditId;
        this.state = state;
        this.verifiedAt = verifiedAt;
        this.actorUserId = actorUserId;
    }
}
