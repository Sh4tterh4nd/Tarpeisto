package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Immutable checkout header; exact facts live in its child rows. */
@Entity
@Table(name = "checkout_manifest")
public class CheckoutManifest {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "event_booking_id")
    private UUID bookingId;

    @Column(name = "reservation_revision_id")
    private UUID reservationRevisionId;

    @Column(name = "checkout_mutation_id")
    private UUID mutationId;

    @Column(name = "checkout_fingerprint")
    private String fingerprint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "booking_snapshot", columnDefinition = "jsonb")
    private String bookingSnapshot;

    @Column(name = "checked_out_by_user_id")
    private UUID checkedOutByUserId;

    @Column(name = "checked_out_at")
    private Instant checkedOutAt;

    protected CheckoutManifest() {}

    public CheckoutManifest(
            UUID id,
            UUID organizationId,
            UUID bookingId,
            UUID reservationRevisionId,
            UUID mutationId,
            String fingerprint,
            String bookingSnapshot,
            UUID actor,
            Instant at) {
        this.id = id;
        this.organizationId = organizationId;
        this.bookingId = bookingId;
        this.reservationRevisionId = reservationRevisionId;
        this.mutationId = mutationId;
        this.fingerprint = fingerprint;
        this.bookingSnapshot = bookingSnapshot;
        this.checkedOutByUserId = actor;
        this.checkedOutAt = at;
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

    public UUID getReservationRevisionId() {
        return reservationRevisionId;
    }

    public UUID getMutationId() {
        return mutationId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getBookingSnapshot() {
        return bookingSnapshot;
    }

    public UUID getCheckedOutByUserId() {
        return checkedOutByUserId;
    }

    public Instant getCheckedOutAt() {
        return checkedOutAt;
    }
}
