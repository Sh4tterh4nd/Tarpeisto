package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Frozen exact identity plus separately mutable return fact. */
@Entity
@Table(name = "checkout_manifest_asset")
public class CheckoutManifestAsset {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "checkout_manifest_id")
    private UUID manifestId;

    @Column(name = "physical_asset_id")
    private UUID assetId;

    @Column(name = "source_booking_line_id")
    private UUID sourceBookingLineId;

    @Column(name = "container_asset_id")
    private UUID containerAssetId;

    @Column(name = "physical_parent_container_asset_id")
    private UUID physicalParentContainerAssetId;

    @Column(name = "is_container")
    private boolean container;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_snapshot", columnDefinition = "jsonb")
    private String assetSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "container_snapshot", columnDefinition = "jsonb")
    private String containerSnapshot;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @Column(name = "returned_by_user_id")
    private UUID returnedByUserId;

    @Column(name = "return_mutation_id")
    private UUID returnMutationId;

    @Column(name = "audit_released_at")
    private Instant auditReleasedAt;

    protected CheckoutManifestAsset() {}

    public CheckoutManifestAsset(
            UUID id,
            UUID org,
            UUID manifestId,
            UUID assetId,
            UUID lineId,
            UUID containerId,
            UUID physicalParentId,
            boolean container,
            String assetSnapshot,
            String containerSnapshot) {
        this.id = id;
        organizationId = org;
        this.manifestId = manifestId;
        this.assetId = assetId;
        sourceBookingLineId = lineId;
        containerAssetId = containerId;
        physicalParentContainerAssetId = physicalParentId;
        this.container = container;
        this.assetSnapshot = assetSnapshot;
        this.containerSnapshot = containerSnapshot;
    }

    public void markReturned(UUID actor, UUID mutationId, Instant at) {
        if (returnedAt == null) {
            returnedAt = at;
            returnedByUserId = actor;
            returnMutationId = mutationId;
        }
    }

    /** Phase 9 invokes this only after required audit/review work clears the asset. */
    public void releaseAfterAudit(Instant at) {
        if (returnedAt == null) throw new IllegalStateException("An asset must be returned before releasing custody.");
        if (auditReleasedAt == null) auditReleasedAt = at;
    }

    /** Formal loss/destruction accounting releases custody without inventing a physical return. */
    public void releaseThroughFormalAccounting(Instant at) {
        if (returnedAt != null) {
            throw new IllegalStateException("Physical returns must be released through audit/review, not accounting.");
        }
        if (auditReleasedAt == null) auditReleasedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getManifestId() {
        return manifestId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getSourceBookingLineId() {
        return sourceBookingLineId;
    }

    public UUID getContainerAssetId() {
        return containerAssetId;
    }

    public UUID getPhysicalParentContainerAssetId() {
        return physicalParentContainerAssetId;
    }

    public boolean isContainer() {
        return container;
    }

    public String getAssetSnapshot() {
        return assetSnapshot;
    }

    public String getContainerSnapshot() {
        return containerSnapshot;
    }

    public Instant getReturnedAt() {
        return returnedAt;
    }

    public UUID getReturnMutationId() {
        return returnMutationId;
    }

    public Instant getAuditReleasedAt() {
        return auditReleasedAt;
    }
}
