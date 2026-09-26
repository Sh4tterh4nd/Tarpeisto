package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "event_booking")
public class Booking {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "name")
    private String name;

    @Column(name = "client_text")
    private String clientText;

    @Column(name = "venue_text")
    private String venueText;

    @Column(name = "notes")
    private String notes;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private BookingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "reservation_status")
    private BookingReservationStatus reservationStatus;

    @Column(name = "current_revision_id")
    private UUID currentRevisionId;

    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    @Column(name = "creation_mutation_id", updatable = false)
    private UUID creationMutationId;

    public UUID getCreationMutationId() {
        return creationMutationId;
    }

    @Column(name = "creation_fingerprint", updatable = false)
    private String creationFingerprint;

    public String getCreationFingerprint() {
        return creationFingerprint;
    }

    public void identifyCreation(UUID mutationId, String fingerprint) {
        if (creationMutationId != null) throw new IllegalStateException("Creation command identity is immutable.");
        creationMutationId = Objects.requireNonNull(mutationId);
        creationFingerprint = Objects.requireNonNull(fingerprint);
    }

    protected Booking() {}

    public Booking(
            UUID id,
            UUID organizationId,
            String name,
            String clientText,
            String venueText,
            String notes,
            Instant startsAt,
            Instant endsAt,
            UUID actor,
            Instant now) {
        this.id = Objects.requireNonNull(id);
        this.organizationId = Objects.requireNonNull(organizationId);
        this.createdByUserId = Objects.requireNonNull(actor);
        change(name, clientText, venueText, notes, startsAt, endsAt, now);
        status = BookingStatus.DRAFT;
        reservationStatus = BookingReservationStatus.NONE;
        createdAt = now;
    }

    public void change(
            String name,
            String clientText,
            String venueText,
            String notes,
            Instant startsAt,
            Instant endsAt,
            Instant now) {
        if (name == null || name.isBlank() || name.trim().length() > 160)
            throw new IllegalArgumentException("A booking name of at most 160 characters is required.");
        if (startsAt == null || endsAt == null || !startsAt.isBefore(endsAt))
            throw new IllegalArgumentException("Booking end must be after its start.");
        this.name = name.trim();
        this.clientText = text(clientText);
        this.venueText = text(venueText);
        this.notes = text(notes);
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        updatedAt = Objects.requireNonNull(now);
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public void reserve(Instant now) {
        if (status != BookingStatus.DRAFT) throw new IllegalStateException("Only draft bookings can be reserved.");
        status = BookingStatus.RESERVED;
        updatedAt = now;
    }

    public void applyRevision(UUID revisionId, boolean valid, Instant now) {
        currentRevisionId = revisionId;
        reservationStatus = valid ? BookingReservationStatus.CONFIRMED : BookingReservationStatus.ATTENTION_REQUIRED;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        if (status != BookingStatus.DRAFT && status != BookingStatus.RESERVED)
            throw new IllegalStateException("Only draft or reserved bookings can be cancelled.");
        status = BookingStatus.CANCELLED;
        reservationStatus = BookingReservationStatus.NONE;
        updatedAt = now;
    }

    /** Phase 8 custody transition; the immutable manifest is created in the same transaction. */
    public void checkOut(Instant now) {
        if (status != BookingStatus.RESERVED) {
            throw new IllegalStateException("Only reserved bookings can be checked out.");
        }
        status = BookingStatus.CHECKED_OUT;
        updatedAt = Objects.requireNonNull(now);
    }

    /** Return facts are separate from the manifest and imply a Phase-9 audit is required. */
    public void markReturnedAuditsPending(Instant now) {
        if (status != BookingStatus.CHECKED_OUT && status != BookingStatus.RETURNED_AUDITS_PENDING) {
            throw new IllegalStateException("Only checked-out bookings can be checked in.");
        }
        status = BookingStatus.RETURNED_AUDITS_PENDING;
        updatedAt = Objects.requireNonNull(now);
    }

    public void touch(Instant now) {
        updatedAt = Objects.requireNonNull(now);
    }

    public void markReviewRequired(Instant now) {
        if (status != BookingStatus.CHECKED_OUT
                && status != BookingStatus.RETURNED_AUDITS_PENDING
                && status != BookingStatus.REVIEW_REQUIRED)
            throw new IllegalStateException("Booking is not awaiting return review.");
        status = BookingStatus.REVIEW_REQUIRED;
        updatedAt = Objects.requireNonNull(now);
    }

    public void completeReturn(Instant now) {
        if (status != BookingStatus.CHECKED_OUT
                && status != BookingStatus.RETURNED_AUDITS_PENDING
                && status != BookingStatus.REVIEW_REQUIRED)
            throw new IllegalStateException("Booking is not awaiting return.");
        status = BookingStatus.COMPLETED;
        reservationStatus = BookingReservationStatus.NONE;
        updatedAt = Objects.requireNonNull(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getClientText() {
        return clientText;
    }

    public String getVenueText() {
        return venueText;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BookingReservationStatus getReservationStatus() {
        return reservationStatus;
    }

    public UUID getCurrentRevisionId() {
        return currentRevisionId;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
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
}
