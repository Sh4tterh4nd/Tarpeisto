package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.CustomFieldDataType;
import io.kellermann.tarpeisto.model.ModelCustomField;
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
