package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.CatalogSearchService;
import java.util.UUID;

public record ModelSearchResponse(
        UUID id,
        String name,
        String trackingMode,
        UUID categoryId,
        String categoryName,
        String stockUnitLabel,
        boolean archived) {
    static ModelSearchResponse from(CatalogSearchService.ModelView r) {
        return new ModelSearchResponse(
                r.id(), r.name(), r.trackingMode(), r.categoryId(), r.categoryName(), r.stockUnitLabel(), r.archived());
    }
}
