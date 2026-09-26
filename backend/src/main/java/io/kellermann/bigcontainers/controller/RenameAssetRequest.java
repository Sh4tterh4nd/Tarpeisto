package io.kellermann.bigcontainers.controller;

/** Request body for {@code PUT /api/v1/assets/{assetId}/name}. */
public record RenameAssetRequest(String individualName) {}
