package io.kellermann.bigcontainers.controller;

import java.time.LocalDate;
import java.util.List;

public record CreateReplacementAssetRequest(
        String individualName, LocalDate purchaseDate, List<AssetCustomFieldValueRequest> values) {}
