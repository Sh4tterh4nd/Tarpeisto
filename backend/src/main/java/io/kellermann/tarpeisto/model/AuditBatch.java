package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Phase-8 creation fact; audit execution is deliberately a Phase-9 responsibility. */
@Entity
@Table(name = "audit_batch")
public class AuditBatch {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "event_booking_id")
    private UUID bookingId;

    @Column(name = "checkout_manifest_id")
    private UUID manifestId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    protected AuditBatch() {}

    public AuditBatch(UUID id, UUID org, UUID bookingId, UUID manifestId, UUID actor, Instant createdAt) {
        this.id = id;
        organizationId = org;
        this.bookingId = bookingId;
        this.manifestId = manifestId;
        createdByUserId = actor;
        this.createdAt = createdAt;
    }

    /** A standalone inventory audit has no event booking or checkout manifest. */
    public static AuditBatch standalone(UUID id, UUID org, UUID actor, Instant createdAt) {
        return new AuditBatch(id, org, null, null, actor, createdAt);
    }

    public boolean isEventReturnBatch() {
        return bookingId != null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getManifestId() {
        return manifestId;
    }
}
