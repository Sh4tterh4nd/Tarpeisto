package io.kellermann.tarpeisto.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * The value of one model-defined {@link ModelCustomField} for one {@link Asset} (specification
 * section 7): "Custom-field definitions are created on a serialized model, but their values exist
 * only on physical assets of that model." Exactly one of {@link #stringValue}, {@link
 * #dateValue}, or {@link #optionId} is set, matching the owning field's {@link
 * CustomFieldDataType} - mirrored by the {@code tr_asset_custom_field_value_validate} database
 * trigger in {@code V5__create_asset_schema.sql} as the backstop
 * (docs/DEVELOPMENT_POLICIES.md section 5.2).
 *
 * <p>Whether a referenced dropdown option is still active, and whether it belongs to the correct
 * field, are validated by {@code AssetService} before construction/{@link #update} is called (the
 * option-belongs-to-field half is additionally enforced by a composite foreign key in the
 * migration); this entity only enforces the "exactly one value slot is set" shape.
 */
@Entity
@Table(name = "asset_custom_field_value")
public class AssetCustomFieldValue {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "model_custom_field_id", nullable = false, updatable = false)
    private UUID modelCustomFieldId;

    @Column(name = "string_value")
    private String stringValue;

    @Column(name = "date_value")
    private LocalDate dateValue;

    @Column(name = "option_id")
    private UUID optionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AssetCustomFieldValue() {
        // JPA
    }

    public AssetCustomFieldValue(
            UUID id,
            UUID organizationId,
            UUID assetId,
            UUID modelCustomFieldId,
            CustomFieldDataType dataType,
            String stringValue,
            LocalDate dateValue,
            UUID optionId,
            Instant now) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.assetId = Objects.requireNonNull(assetId, "assetId must not be null");
        this.modelCustomFieldId = Objects.requireNonNull(modelCustomFieldId, "modelCustomFieldId must not be null");
        requireShapeMatchesDataType(dataType, stringValue, dateValue, optionId);
        this.stringValue = stringValue;
        this.dateValue = dateValue;
        this.optionId = optionId;
        this.createdAt = Objects.requireNonNull(now, "now must not be null");
        this.updatedAt = now;
    }

    /** Replaces this value in place, keeping the (asset, field) pairing (upsert semantics). */
    public void update(
            CustomFieldDataType dataType,
            String newStringValue,
            LocalDate newDateValue,
            UUID newOptionId,
            Instant now) {
        requireShapeMatchesDataType(dataType, newStringValue, newDateValue, newOptionId);
        this.stringValue = newStringValue;
        this.dateValue = newDateValue;
        this.optionId = newOptionId;
        this.updatedAt = Objects.requireNonNull(now, "now must not be null");
    }

    private static void requireShapeMatchesDataType(
            CustomFieldDataType dataType, String stringValue, LocalDate dateValue, UUID optionId) {
        Objects.requireNonNull(dataType, "dataType must not be null");
        switch (dataType) {
            case STRING -> {
                if (stringValue == null || stringValue.isBlank() || dateValue != null || optionId != null) {
                    throw new IllegalArgumentException("A STRING custom field value must set only stringValue.");
                }
            }
            case DATE -> {
                if (dateValue == null || stringValue != null || optionId != null) {
                    throw new IllegalArgumentException("A DATE custom field value must set only dateValue.");
                }
            }
            case DROPDOWN -> {
                if (optionId == null || stringValue != null || dateValue != null) {
                    throw new IllegalArgumentException("A DROPDOWN custom field value must set only optionId.");
                }
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getAssetId() {
        return assetId;
    }

    public UUID getModelCustomFieldId() {
        return modelCustomFieldId;
    }

    public String getStringValue() {
        return stringValue;
    }

    public LocalDate getDateValue() {
        return dateValue;
    }

    public UUID getOptionId() {
        return optionId;
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
        if (!(other instanceof AssetCustomFieldValue that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
