package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Immutable domain history supplementing the organization activity log. */
@Entity
@Table(name = "packing_requirement_history")
public class PackingRequirementHistory {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "packing_requirement_id")
    private UUID packingRequirementId;

    @Column(name = "packing_template_requirement_id", updatable = false)
    private UUID packingTemplateRequirementId;

    @Column(name = "details", nullable = false, updatable = false, columnDefinition = "text")
    private String details;

    @Column(name = "action")
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    protected PackingRequirementHistory() {}

    public PackingRequirementHistory(
            UUID id,
            UUID organizationId,
            UUID requirementId,
            UUID templateRequirementId,
            String action,
            UUID actorUserId,
            Instant occurredAt,
            String details) {
        this.id = id;
        this.organizationId = organizationId;
        packingRequirementId = requirementId;
        packingTemplateRequirementId = templateRequirementId;
        this.details = java.util.Objects.requireNonNull(details);
        this.action = action;
        this.actorUserId = actorUserId;
        this.occurredAt = occurredAt;
    }
}
