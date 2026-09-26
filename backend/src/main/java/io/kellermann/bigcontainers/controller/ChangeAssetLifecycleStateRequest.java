package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.LifecycleState;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code PUT /api/v1/assets/{assetId}/lifecycle}. */
public record ChangeAssetLifecycleStateRequest(@NotNull LifecycleState lifecycleState, String reason) {}
