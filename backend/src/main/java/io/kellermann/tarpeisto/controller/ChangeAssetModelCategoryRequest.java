package io.kellermann.tarpeisto.controller;

import java.util.UUID;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}/category}. */
public record ChangeAssetModelCategoryRequest(UUID categoryId) {}
