package io.kellermann.bigcontainers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A colored classification assigned to an {@link AssetModel} (specification section 5). Category
 * names are unique within an organization while active; archiving keeps the category resolvable
 * from historical records but removes it from selection for new models (enforced in
 * {@code CategoryService}/{@code AssetModelService}, not here).
 */
@Entity
@Table(name = "category")
public class Category {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9A-F]{6}$");

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "color", nullable = false, length = 7)
    private String color;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Category() {
        // JPA
    }

    public Category(UUID id, UUID organizationId, String name, String color, Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.name = requireNonBlankName(name);
        this.color = normalizeColor(color);
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void rename(String newName, String newColor, Instant now) {
        this.name = requireNonBlankName(newName);
        this.color = normalizeColor(newColor);
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public void archive(Instant now) {
        this.archivedAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    public void restore(Instant now) {
        this.archivedAt = null;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    /**
     * Normalizes a color to {@code #RRGGBB} uppercase (specification section 5: "Color as
     * normalized #RRGGBB"). A leading {@code #} is optional on input and always present on output.
     */
    public static String normalizeColor(String color) {
        if (color == null) {
            throw new IllegalArgumentException("color must not be null");
        }
        String trimmed = color.trim();
        String withHash = trimmed.startsWith("#") ? trimmed : "#" + trimmed;
        String upper = withHash.toUpperCase(Locale.ROOT);
        if (!HEX_COLOR.matcher(upper).matches()) {
            throw new IllegalArgumentException("color must be a normalized #RRGGBB value: " + color);
        }
        return upper;
    }

    private static String requireNonBlankName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name.trim();
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

    public String getColor() {
        return color;
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
        if (!(other instanceof Category that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
