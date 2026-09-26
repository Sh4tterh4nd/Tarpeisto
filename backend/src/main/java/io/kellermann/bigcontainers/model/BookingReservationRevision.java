package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only reservation fact. Database triggers prohibit rewrites and deletes. */
@Entity
@Table(name = "event_booking_reservation_revision")
public class BookingReservationRevision {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "event_booking_id")
    private UUID bookingId;

    @Column(name = "revision_number")
    private int revisionNumber;

    @Column(name = "action")
    private String action;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "occurred_at")
    private Instant occurredAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    protected BookingReservationRevision() {}

    public BookingReservationRevision(
            UUID id,
            UUID org,
            UUID booking,
            int revision,
            String action,
            UUID actor,
            Instant occurredAt,
            String details) {
        this.id = id;
        organizationId = org;
        bookingId = booking;
        revisionNumber = revision;
        this.action = action;
        actorUserId = actor;
        this.occurredAt = occurredAt;
        this.details = details;
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

    public int getRevisionNumber() {
        return revisionNumber;
    }

    public String getAction() {
        return action;
    }
}
