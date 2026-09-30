package io.kellermann.tarpeisto.repository;

import java.util.UUID;

public record CatalogSearchProjection(
        UUID id,
        String name,
        String trackingMode,
        UUID categoryId,
        String categoryName,
        String stockUnitLabel,
        boolean archived,
        String sortAnchor) {}
