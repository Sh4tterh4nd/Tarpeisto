package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}/custom-fields/{fieldId}}. */
public record RenameModelCustomFieldRequest(@NotBlank String name) {}
