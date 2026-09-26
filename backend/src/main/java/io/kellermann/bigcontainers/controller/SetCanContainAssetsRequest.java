package io.kellermann.bigcontainers.controller;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}/can-contain-assets}. */
public record SetCanContainAssetsRequest(boolean canContainAssets) {}
