package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable formal reconciliation of a missing manifest item, never a substitute for returned_at. */
@Entity
@Table(name = "manifest_asset_accounting")
public class ManifestAssetAccounting {
    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "checkout_manifest_asset_id", updatable = false)
    private UUID manifestAssetId;

    @Column(name = "audit_finding_resolution_id", updatable = false)
    private UUID resolutionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "accounting_state", updatable = false)
    private LifecycleState accountingState;

    @Column(name = "accounted_by_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "accounted_at", updatable = false)
    private Instant accountedAt;

    protected ManifestAssetAccounting() {}

    public ManifestAssetAccounting(
            UUID id,
            UUID organizationId,
            UUID manifestAssetId,
            UUID resolutionId,
            LifecycleState accountingState,
            UUID actorUserId,
            Instant accountedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.manifestAssetId = manifestAssetId;
        this.resolutionId = resolutionId;
        this.accountingState = accountingState;
        this.actorUserId = actorUserId;
        this.accountedAt = accountedAt;
    }
}
