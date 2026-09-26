package io.kellermann.bigcontainers.controller;

/** Request body for {@code PUT /api/v1/asset-models/{assetModelId}/replacement-url}. */
public record ChangeReplacementUrlRequest(String replacementUrl) {}
