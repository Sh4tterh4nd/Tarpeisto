package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * One individually tracked, real-world unit of a {@link TrackingMode#SERIALIZED_ASSET} {@link
 * AssetModel} (specification section 8). Never named {@code PhysicalAsset} in Java - see
 * docs/DEVELOPMENT_POLICIES.md section 4.1 ("Use {@code Asset} for one physical unit").
 *
 * <p>Deliberately carries no direct-location or current-parent-container column: specification
 * sections 10-11 (locations and physical containment) are Phase 4's subject, added together with
 * the {@code location} table and its cycle-safety rules. Adding a dangling column now would only
 * have to be reworked (Phase 2b task scope).
 *
 * <p>{@link #condition} and {@link #lifecycleState} are independent axes (specification section
 * 8.3: "Condition ... is separate from lifecycle and operational availability"). Both default at
 * construction time and change only through {@link #changeCondition} / {@link
 * #changeLifecycleState}, which return the previous value so {@code AssetService} can append an
 * immutable {@link AssetStateChange} row in the same transaction.
 */
@Entity
@Table(name = "physical_asset")
public class Asset {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_model_id", nullable = false, updatable = false)
    private UUID assetModelId;

    /** Immutable once assigned (ADR-0002: "Codes are immutable and never reused"). */
    @Column(name = "public_code", nullable = false, updatable = false, length = 16)
    private String publicCode;

    /** Immutable once assigned - model-local unit numbers are sequential and never reassigned. */
    @Column(name = "unit_number", nullable = false, updatable = false)
    private int unitNumber;

    @Column(name = "individual_name")
    private String individualName;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 20)
    private Condition condition;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state", nullable = false, length = 20)
    private LifecycleState lifecycleState;

    @Column(name = "container_color", nullable = false, length = 7)
    private String containerColor = "#FFFFFF";

    @Column(name = "unit_description", length = 500)
    private String unitDescription;

    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "direct_location_id")
    private UUID directLocationId;

    @Column(name = "parent_container_asset_id")
    private UUID parentContainerAssetId;

    @Column(name = "sealable", nullable = false)
    private boolean sealable;

    @Enumerated(EnumType.STRING)
    @Column(name = "seal_state", nullable = false, length = 20)
    private SealState sealState = SealState.UNSEALED;

    @Column(name = "seal_verified_at")
    private Instant sealVerifiedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "last_verified_audit_id")
    private UUID lastVerifiedAuditId;

    @Column(name = "replaces_asset_id")
    private UUID replacesAssetId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Asset() {
        // JPA
    }

    /**
     * Creates a new asset with the default condition ({@code GOOD}) and lifecycle state ({@code
     * ACTIVE}) required by specification section 8.1. {@code AssetService} is responsible for
     * generating {@code publicCode} (via {@code AssetCodeGenerationService}) and {@code
     * unitNumber} (via {@code AssetUnitNumberSequenceRepository}) before calling this, and for the
     * rule that a container-capable model's asset requires an individual name (specification
     * section 8.2) - that rule depends on the owning {@code AssetModel}, which this entity does
     * not reference beyond its id.
     */
    public Asset(
            UUID id,
            UUID organizationId,
            UUID assetModelId,
            String publicCode,
            int unitNumber,
            String individualName,
            LocalDate purchaseDate,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.assetModelId = Objects.requireNonNull(assetModelId, "assetModelId must not be null");
        this.publicCode = requireValidPublicCode(publicCode);
        this.unitNumber = requirePositiveUnitNumber(unitNumber);
        this.individualName = blankToNull(individualName);
        this.condition = Condition.GOOD;
        this.lifecycleState = LifecycleState.ACTIVE;
        this.purchaseDate = purchaseDate;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void setPackingDetails(String color, String description, Instant now) {
        String normalized = Category.normalizeColor(color);
        String normalizedDescription = normalizeUnitDescription(description);
        this.containerColor = normalized;
        this.unitDescription = normalizedDescription;
        touch(now);
    }

    public static String normalizeUnitDescription(String description) {
        String text =
                description == null ? null : description.replace("\r\n", "\n").replace("\r", "\n");
        if (text != null
                && (text.length() > 500
                        || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')))
            throw new IllegalArgumentException(
                    "Description must contain at most 500 characters and no control characters.");
        return text == null || text.isBlank() ? null : text;
    }

    public String getContainerColor() {
        return containerColor;
    }

    public String getUnitDescription() {
        return unitDescription;
    }

    public void rename(String newIndividualName, Instant now) {
        this.individualName = blankToNull(newIndividualName);
        touch(now);
    }

    public void setPurchaseDate(LocalDate newPurchaseDate, Instant now) {
        this.purchaseDate = newPurchaseDate;
        touch(now);
    }

    /** Sets exactly one direct physical placement, or neither for an explicitly unplaced asset. */
    public void moveTo(UUID locationId, UUID containerAssetId, Instant now) {
        if (locationId != null && containerAssetId != null) {
            throw new IllegalArgumentException("An asset cannot have both a location and a parent container.");
        }
        this.directLocationId = locationId;
        this.parentContainerAssetId = containerAssetId;
        touch(now);
    }

    /** Returns the previous condition so the caller can record an {@link AssetStateChange}. */
    public Condition changeCondition(Condition newCondition, Instant now) {
        Objects.requireNonNull(newCondition, "newCondition must not be null");
        if (newCondition == this.condition) {
            throw new IllegalArgumentException("Asset already has condition " + newCondition);
        }
        Condition previous = this.condition;
        this.condition = newCondition;
        touch(now);
        return previous;
    }

    /** Returns the previous lifecycle state so the caller can record an {@link AssetStateChange}. */
    public LifecycleState changeLifecycleState(LifecycleState newLifecycleState, Instant now) {
        Objects.requireNonNull(newLifecycleState, "newLifecycleState must not be null");
        if (newLifecycleState == this.lifecycleState) {
            throw new IllegalArgumentException("Asset already has lifecycle state " + newLifecycleState);
        }
        if (this.lifecycleState == LifecycleState.DESTROYED) {
            throw new IllegalArgumentException("Destroyed assets are terminal.");
        }
        LifecycleState previous = this.lifecycleState;
        this.lifecycleState = newLifecycleState;
        touch(now);
        return previous;
    }

    public void archive(Instant now) {
        this.archivedAt = Objects.requireNonNull(now, "now must not be null");
        touch(now);
    }

    public void restore(Instant now) {
        this.archivedAt = null;
        touch(now);
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /** Excluded from normal inventory results (specification section 8.4). */
    public boolean isActive() {
        return !isArchived() && lifecycleState == LifecycleState.ACTIVE;
    }

    /** Only a container-capable asset may be configured as sealable; its service checks capability. */
    public void setSealable(boolean sealable, Instant now) {
        this.sealable = sealable;
        if (!sealable) {
            this.sealState = SealState.UNSEALED;
            this.sealVerifiedAt = null;
        }
        touch(now);
    }

    public void applySeal(Instant now) {
        if (!sealable) throw new IllegalStateException("This asset is not sealable.");
        sealState = SealState.APPLIED;
        sealVerifiedAt = null;
        touch(now);
    }

    public void breakSeal(Instant now) {
        if (!sealable) throw new IllegalStateException("This asset is not sealable.");
        sealState = SealState.BROKEN;
        sealVerifiedAt = null;
        touch(now);
    }

    public void verifySeal(Instant now) {
        if (!sealable || sealState != SealState.APPLIED)
            throw new IllegalStateException("Only an applied seal can be verified.");
        sealState = SealState.VERIFIED;
        sealVerifiedAt = now;
        touch(now);
    }

    public void invalidateSeal(Instant now) {
        if (sealable && sealState != SealState.UNSEALED) {
            sealState = SealState.INVALIDATED;
            sealVerifiedAt = null;
            touch(now);
        }
    }

    public void recordVerification(UUID auditId, Instant now) {
        lastVerifiedAuditId = Objects.requireNonNull(auditId, "auditId must not be null");
        lastVerifiedAt = Objects.requireNonNull(now, "now must not be null");
        touch(now);
    }

    public void invalidateVerification(Instant now) {
        lastVerifiedAuditId = null;
        lastVerifiedAt = null;
        touch(now);
    }

    public void setReplacementPredecessor(UUID predecessorId, Instant now) {
        if (replacesAssetId != null) throw new IllegalStateException("The replacement predecessor is immutable.");
        replacesAssetId = Objects.requireNonNull(predecessorId, "predecessorId must not be null");
        touch(now);
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireValidPublicCode(String publicCode) {
        // Delegates to AssetCode's own compact-constructor validation (ADR-0002) rather than
        // duplicating the checksum/alphabet/length rules here.
        return new AssetCode(publicCode).value();
    }

    private static int requirePositiveUnitNumber(int unitNumber) {
        if (unitNumber <= 0) {
            throw new IllegalArgumentException("unitNumber must be positive: " + unitNumber);
        }
        return unitNumber;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAssetModelId() {
        return assetModelId;
    }

    public String getPublicCode() {
        return publicCode;
    }

    public int getUnitNumber() {
        return unitNumber;
    }

    public String getIndividualName() {
        return individualName;
    }

    public Condition getCondition() {
        return condition;
    }

    public LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public LocalDate getPurchaseDate() {
        return purchaseDate;
    }

    public UUID getDirectLocationId() {
        return directLocationId;
    }

    public UUID getParentContainerAssetId() {
        return parentContainerAssetId;
    }

    public boolean isSealable() {
        return sealable;
    }

    public SealState getSealState() {
        return sealState;
    }

    public Instant getSealVerifiedAt() {
        return sealVerifiedAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public UUID getLastVerifiedAuditId() {
        return lastVerifiedAuditId;
    }

    public UUID getReplacesAssetId() {
        return replacesAssetId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Asset that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
