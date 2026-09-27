package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable, append-only record of an {@link Asset} condition or lifecycle transition
 * (specification sections 8.3/8.4, 25: "Lifecycle and condition changes" are part of required
 * history). Rows are never updated or deleted by application code, mirroring {@link ActivityLog}'s
 * immutability - there are deliberately no setters here beyond the constructor.
 *
 * <p>This is a dedicated, structured table rather than a generic {@link ActivityLog} entry so that
 * an asset's condition/lifecycle timeline can be queried and rendered without parsing {@link
 * ActivityLog#getDetail()} JSON; {@code AssetService} still also writes an {@link ActivityLog}
 * entry for the same change, per the existing pattern every other mutating service follows.
 */
@Entity
@Table(name = "asset_state_history")
public class AssetStateChange {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 20)
    private AssetStateChangeType changeType;

    @Column(name = "previous_value", nullable = false, updatable = false, length = 20)
    private String previousValue;

    @Column(name = "new_value", nullable = false, updatable = false, length = 20)
    private String newValue;

    @Column(name = "reason", updatable = false, columnDefinition = "text")
    private String reason;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected AssetStateChange() {
        // JPA
    }

    public AssetStateChange(
            UUID id,
            UUID organizationId,
            UUID assetId,
            AssetStateChangeType changeType,
            String previousValue,
            String newValue,
            String reason,
            UUID actorUserId,
            Instant changedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.assetId = Objects.requireNonNull(assetId, "assetId must not be null");
        this.changeType = Objects.requireNonNull(changeType, "changeType must not be null");
        this.previousValue = Objects.requireNonNull(previousValue, "previousValue must not be null");
        this.newValue = Objects.requireNonNull(newValue, "newValue must not be null");
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        this.actorUserId = actorUserId;
        this.changedAt = Objects.requireNonNull(changedAt, "changedAt must not be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public AssetStateChangeType getChangeType() {
        return changeType;
    }

    public String getPreviousValue() {
        return previousValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AssetStateChange that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
