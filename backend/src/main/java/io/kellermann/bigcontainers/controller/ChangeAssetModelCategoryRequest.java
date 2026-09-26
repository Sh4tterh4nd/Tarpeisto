package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}/category}. */
public record ChangeAssetModelCategoryRequest(@NotNull UUID categoryId) {}
