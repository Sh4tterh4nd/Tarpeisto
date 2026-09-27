package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "packing_template")
public class PackingTemplate {
    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "organization_id")
    private UUID organizationId;

    @Column(name = "name", length = 160)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected PackingTemplate() {}

    public PackingTemplate(UUID id, UUID org, String name, String description, Instant now) {
        this.id = id;
        this.organizationId = org;
        rename(name, description, now);
        this.createdAt = now;
    }

    public void rename(String value, String nextDescription, Instant now) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("A template name is required.");
        name = value.trim();
        description = nextDescription == null || nextDescription.isBlank() ? null : nextDescription.trim();
        updatedAt = now;
    }

    public void archive(Instant now) {
        archivedAt = now;
        updatedAt = now;
    }

    public void restore(Instant now) {
        archivedAt = null;
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

    public boolean isArchived() {
        return archivedAt != null;
    }

    public long getVersion() {
        return version;
    }
}
