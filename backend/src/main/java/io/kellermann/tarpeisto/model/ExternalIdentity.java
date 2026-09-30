package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Maps an OIDC {@code (issuer, subject)} pair to an internal {@link User} (ADR-0003, specification
 * section 4.3/28), created and mutated by {@code
 * io.kellermann.tarpeisto.service.ExternalIdentityService}.
 *
 * <p>The durable identity key is the immutable pair {@code (issuer, subject)} - {@code issuer} and
 * {@code subject} are {@code updatable = false} and never change after construction - enforced by
 * a unique database constraint. {@code lastEmail}/{@code lastDisplayName} are diagnostic/
 * profile-display attributes only, refreshed on every login via {@link #recordLogin}, and are
 * never the identity key: a later email change never alters which internal user this row is linked
 * to.
 *
 * <p>{@link #unlink(Instant)} soft-unlinks a row (Owner action) rather than deleting it, both to
 * keep an audit trail and so the {@code (issuer, subject)} unique constraint continues to prevent
 * two simultaneously-active links for the same external identity. {@link #relink(UUID, Instant)}
 * reactivates a previously unlinked row for a (possibly different) user rather than inserting a
 * second row for the same {@code (issuer, subject)}, which the unique constraint would reject.
 */
@Entity
@Table(name = "external_identity")
public class ExternalIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "issuer", nullable = false, updatable = false)
    private String issuer;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "last_email")
    private String lastEmail;

    @Column(name = "last_display_name")
    private String lastDisplayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "unlinked_at")
    private Instant unlinkedAt;

    protected ExternalIdentity() {
        // JPA
    }

    public ExternalIdentity(
            UUID id,
            UUID userId,
            String issuer,
            String subject,
            String lastEmail,
            String lastDisplayName,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.issuer = requireNonBlank(issuer, "issuer");
        this.subject = requireNonBlank(subject, "subject");
        this.lastEmail = lastEmail;
        this.lastDisplayName = lastDisplayName;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /** Whether this row currently represents a usable link (never unlinked). */
    public boolean isActive() {
        return unlinkedAt == null;
    }

    /** Refreshes the diagnostic profile attributes and last-login timestamp on a successful login. */
    public void recordLogin(String email, String displayName, Instant now) {
        this.lastEmail = email;
        this.lastDisplayName = displayName;
        this.lastLoginAt = Objects.requireNonNull(now, "now must not be null");
    }

    /** Soft-unlinks this identity (Owner action): {@link #isActive()} becomes {@code false}. */
    public void unlink(Instant now) {
        this.unlinkedAt = Objects.requireNonNull(now, "now must not be null");
    }

    /**
     * Reactivates a previously unlinked row for {@code userId}, preserving the immutable {@code
     * (issuer, subject)} pair and this row's identity/audit trail rather than inserting a new row.
     */
    public void relink(UUID userId, Instant now) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.unlinkedAt = null;
        Objects.requireNonNull(now, "now must not be null");
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSubject() {
        return subject;
    }

    public String getLastEmail() {
        return lastEmail;
    }

    public String getLastDisplayName() {
        return lastDisplayName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getUnlinkedAt() {
        return unlinkedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExternalIdentity that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
