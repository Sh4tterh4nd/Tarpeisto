package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A physical storage place in one organization-scoped hierarchy. */
@Entity
@Table(name = "location")
public class Location {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "parent_location_id")
    private UUID parentLocationId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Location() {}

    public Location(UUID id, UUID organizationId, String name, String description, UUID parentLocationId, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.organizationId = Objects.requireNonNull(organizationId);
        rename(name, description, now);
        this.parentLocationId = parentLocationId;
        this.createdAt = now;
    }

    public void update(String newName, String newDescription, UUID newParentLocationId, Instant now) {
        rename(newName, newDescription, now);
        this.parentLocationId = newParentLocationId;
    }

    public void archive(Instant now) {
        archivedAt = now;
        updatedAt = now;
    }

    public void restore(Instant now) {
        archivedAt = null;
        updatedAt = now;
    }

    private void rename(String newName, String newDescription, Instant now) {
        if (newName == null || newName.isBlank()) throw new IllegalArgumentException("A location name is required.");
        name = newName.trim();
        description = newDescription == null || newDescription.isBlank() ? null : newDescription.trim();
        updatedAt = now;
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

    public String getDescription() {
        return description;
    }

    public UUID getParentLocationId() {
        return parentLocationId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
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

    public boolean isArchived() {
        return archivedAt != null;
    }
}
