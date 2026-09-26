package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.CustomFieldDataType;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection of one {@code AssetCustomFieldValue}, joined with its defining {@code
 * ModelCustomField} (and, for a dropdown value, its selected {@code ModelCustomFieldOption}) so a
 * client never has to make a second call to render a value meaningfully.
 */
public record AssetCustomFieldValueView(
        UUID fieldId,
        String fieldName,
        CustomFieldDataType dataType,
        int displayOrder,
        String stringValue,
        LocalDate dateValue,
        UUID optionId,
        String optionValue) {}
