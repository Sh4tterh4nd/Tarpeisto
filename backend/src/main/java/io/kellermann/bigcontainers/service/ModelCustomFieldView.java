package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.CustomFieldDataType;
import io.kellermann.bigcontainers.model.ModelCustomField;
import java.util.UUID;

/** Read projection of a {@link ModelCustomField} for the catalog API. */
public record ModelCustomFieldView(
        UUID id, UUID assetModelId, String name, CustomFieldDataType dataType, int displayOrder, boolean archived) {

    public static ModelCustomFieldView from(ModelCustomField field) {
        return new ModelCustomFieldView(
                field.getId(),
                field.getAssetModelId(),
                field.getName(),
                field.getDataType(),
                field.getDisplayOrder(),
                field.isArchived());
    }
}
