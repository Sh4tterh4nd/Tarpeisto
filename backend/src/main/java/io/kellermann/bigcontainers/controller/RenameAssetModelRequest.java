package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}}. */
public record RenameAssetModelRequest(@NotBlank String name, String description) {}
