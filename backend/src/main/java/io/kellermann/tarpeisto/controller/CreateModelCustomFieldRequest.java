package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.CustomFieldDataType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/custom-fields}. */
public record CreateModelCustomFieldRequest(
        @NotBlank String name, @NotNull CustomFieldDataType dataType) {}
