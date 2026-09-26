package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.Condition;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code PUT /api/v1/assets/{assetId}/condition}. */
public record ChangeAssetConditionRequest(@NotNull Condition condition, String reason) {}
