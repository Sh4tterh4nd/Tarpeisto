package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A permanent Tarpeisto principal (specification section 4). A {@link User} is not itself
 * organization-owned: its organization, role, and authorization context come from its {@link
 * OrganizationMembership} row(s), per the Phase 1 data model.
 *
 * <p>{@code passwordHash} is nullable: a user created for a future OIDC-only login (not yet
 * implemented) may have no local credential at all. It is never exposed outside this entity and
 * this package's persistence/authentication code - never logged, never placed in a response DTO,
 * per docs/DEVELOPMENT_POLICIES.md and the Phase 1 task's security constraints.
 */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "email")
    private String email;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected User() {
        // JPA
    }

    public User(
            UUID id,
            String username,
            String email,
            String displayName,
            String passwordHash,
            boolean enabled,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.username = requireNonBlank(username, "username");
        this.email = email;
        this.displayName = requireNonBlank(displayName, "displayName");
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void enable(Instant now) {
        this.enabled = true;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void disable(Instant now) {
        this.enabled = false;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
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

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
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
        if (!(other instanceof User that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
