package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One immutable, append-only activity/history entry (specification section 25): "Activity history
 * records actor, timestamp, action, target, and relevant before/after information." Rows are
 * never updated or deleted by application code - there are deliberately no setters here beyond
 * the constructor.
 *
 * <p>{@code detail} holds a JSON-serialized, freeform before/after summary produced by the
 * recording service; it is a plain {@code TEXT} column rather than {@code jsonb} because Phase 1
 * only ever writes and displays it wholesale and never queries into its structure - see
 * docs/DEVELOPMENT_POLICIES.md section 5.2 ("JSONB is reserved for genuinely document-shaped
 * data... not ordinary relationships or searchable fields").
 */
@Entity
@Table(name = "activity_log")
public class ActivityLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "actor_user_id", updatable = false)
    private UUID actorUserId;

    @Column(name = "action", nullable = false, updatable = false)
    private String action;

    @Column(name = "target_type", nullable = false, updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private UUID targetId;

    @Column(name = "detail", updatable = false, columnDefinition = "text")
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected ActivityLog() {
        // JPA
    }

    public ActivityLog(
            UUID id,
            UUID organizationId,
            UUID actorUserId,
            String action,
            String targetType,
            UUID targetId,
            String detail,
            Instant occurredAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.actorUserId = actorUserId;
        this.action = requireNonBlank(action, "action");
        this.targetType = requireNonBlank(targetType, "targetType");
        this.targetId = targetId;
        this.detail = detail;
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActivityLog that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
