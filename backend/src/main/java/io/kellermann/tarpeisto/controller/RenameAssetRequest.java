package io.kellermann.tarpeisto.controller;

/** Request body for {@code PUT /api/v1/assets/{assetId}/name}. */
public record RenameAssetRequest(String individualName) {}
