package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.CustomFieldDataType;
import io.kellermann.tarpeisto.service.ModelCustomFieldView;
import java.util.UUID;

/** Response element for the model custom-field definition endpoints. */
public record ModelCustomFieldResponse(
        UUID id, UUID assetModelId, String name, CustomFieldDataType dataType, int displayOrder, boolean archived) {

    public static ModelCustomFieldResponse from(ModelCustomFieldView view) {
        return new ModelCustomFieldResponse(
                view.id(), view.assetModelId(), view.name(), view.dataType(), view.displayOrder(), view.archived());
    }
}
