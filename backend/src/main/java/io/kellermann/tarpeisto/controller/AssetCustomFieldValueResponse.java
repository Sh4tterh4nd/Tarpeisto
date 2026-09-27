package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.CustomFieldDataType;
import io.kellermann.tarpeisto.service.AssetCustomFieldValueView;
import java.time.LocalDate;
import java.util.UUID;

/** Response element for one asset custom field value. */
public record AssetCustomFieldValueResponse(
        UUID fieldId,
        String fieldName,
        CustomFieldDataType dataType,
        int displayOrder,
        String stringValue,
        LocalDate dateValue,
        UUID optionId,
        String optionValue) {

    public static AssetCustomFieldValueResponse from(AssetCustomFieldValueView view) {
        return new AssetCustomFieldValueResponse(
                view.fieldId(),
                view.fieldName(),
                view.dataType(),
                view.displayOrder(),
                view.stringValue(),
                view.dateValue(),
                view.optionId(),
                view.optionValue());
    }
}
