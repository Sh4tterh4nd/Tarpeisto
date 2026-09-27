package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.Min;
import java.time.LocalDate;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/assets/bulk}. */
public record BulkCreateAssetsRequest(@Min(1) int count, LocalDate purchaseDate) {}
