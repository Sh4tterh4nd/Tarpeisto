package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.CustomFieldDataType;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code PUT .../custom-fields/{fieldId}/data-type}. */
public record ChangeModelCustomFieldDataTypeRequest(@NotNull CustomFieldDataType dataType) {}
