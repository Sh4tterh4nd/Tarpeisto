package io.kellermann.bigcontainers.controller;

import java.time.LocalDate;
import java.util.List;

/** Request body for {@code POST /api/v1/asset-models/{assetModelId}/assets}. */
public record CreateAssetRequest(
        String individualName, LocalDate purchaseDate, List<AssetCustomFieldValueRequest> values) {}
