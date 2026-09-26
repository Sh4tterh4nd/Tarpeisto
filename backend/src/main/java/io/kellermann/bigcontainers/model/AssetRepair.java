package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An open repair makes its asset unavailable; a closed row is immutable in PostgreSQL. */
@Entity
@Table(name = "asset_repair")
public class AssetRepair {
    @Id
    @Column(name = "id", updatable = false)
    private UUID id;

    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Column(name = "asset_id", updatable = false)
    private UUID assetId;

    @Column(name = "source_finding_id", updatable = false)
    private UUID sourceFindingId;

    @Column(name = "reference_or_description", updatable = false)
    private String referenceOrDescription;

    @Column(name = "opened_by_user_id", updatable = false)
    private UUID openedByUserId;

    @Column(name = "opened_at", updatable = false)
    private Instant openedAt;

    @Column(name = "closed_by_user_id")
    private UUID closedByUserId;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resulting_condition")
    private Condition resultingCondition;

    protected AssetRepair() {}

    public AssetRepair(
            UUID id,
            UUID organizationId,
            UUID assetId,
            UUID sourceFindingId,
            String reference,
            UUID actor,
            Instant at) {
        this.id = Objects.requireNonNull(id);
        this.organizationId = Objects.requireNonNull(organizationId);
        this.assetId = Objects.requireNonNull(assetId);
        this.sourceFindingId = sourceFindingId;
        if (reference == null || reference.isBlank())
            throw new IllegalArgumentException("Repair reference or description is required.");
        this.referenceOrDescription = reference.trim();
        this.openedByUserId = Objects.requireNonNull(actor);
        this.openedAt = Objects.requireNonNull(at);
    }

    public void close(UUID actor, Condition condition, Instant at) {
        if (closedAt != null) throw new IllegalStateException("This repair is already closed.");
        closedByUserId = Objects.requireNonNull(actor);
        resultingCondition = Objects.requireNonNull(condition);
        closedAt = Objects.requireNonNull(at);
    }

    public UUID getId() {
        return id;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getSourceFindingId() {
        return sourceFindingId;
    }

    public String getReferenceOrDescription() {
        return referenceOrDescription;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Condition getResultingCondition() {
        return resultingCondition;
    }
}
