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
 * One selectable value of a {@link CustomFieldDataType#DROPDOWN} {@link ModelCustomField}
 * (specification section 7.2). {@code ModelCustomFieldOptionService} rejects creating one against
 * a field whose data type is not {@code DROPDOWN}.
 */
@Entity
@Table(name = "model_custom_field_option")
public class ModelCustomFieldOption {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "model_custom_field_id", nullable = false, updatable = false)
    private UUID modelCustomFieldId;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ModelCustomFieldOption() {
        // JPA
    }

    public ModelCustomFieldOption(
            UUID id, UUID organizationId, UUID modelCustomFieldId, String value, int displayOrder, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.modelCustomFieldId = Objects.requireNonNull(modelCustomFieldId, "modelCustomFieldId must not be null");
        this.value = requireNonBlankValue(value);
        this.displayOrder = displayOrder;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void rename(String newValue, Instant now) {
        this.value = requireNonBlankValue(newValue);
        touch(now);
    }

    public void reorder(int newDisplayOrder, Instant now) {
        this.displayOrder = newDisplayOrder;
        touch(now);
    }

    public void archive(Instant now) {
        this.archivedAt = Objects.requireNonNull(now, "now must not be null");
        touch(now);
    }

    public void restore(Instant now) {
        this.archivedAt = null;
        touch(now);
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    private void touch(Instant now) {
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static String requireNonBlankValue(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getModelCustomFieldId() {
        return modelCustomFieldId;
    }

    public String getValue() {
        return value;
    }

    public int getDisplayOrder() {
        return displayOrder;
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ModelCustomFieldOption that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
