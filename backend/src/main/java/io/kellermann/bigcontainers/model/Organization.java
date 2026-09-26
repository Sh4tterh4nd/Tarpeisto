package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The tenancy root every organization-owned record belongs to (specification section 3). Phase 0
 * creates exactly one default organization; every later phase's tenant-owned tables reference
 * {@code organization_id} rather than assuming a single hard-coded identifier.
 */
@Entity
@Table(name = "organization")
public class Organization {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Organization() {
        // JPA
    }

    public Organization(UUID id, String name, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = requireNonBlankName(name);
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void rename(String newName, Instant now) {
        this.name = requireNonBlankName(newName);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireNonBlankName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Organization name must not be blank");
        }
        return name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
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
        if (!(other instanceof Organization that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
