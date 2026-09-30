package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.CatalogSearchService;
import java.util.List;

public record ModelSearchPageResponse(List<ModelSearchResponse> items, String nextCursor) {
    static ModelSearchPageResponse from(CatalogSearchService.ModelPage p) {
        return new ModelSearchPageResponse(
                p.items().stream().map(ModelSearchResponse::from).toList(), p.nextCursor());
    }
}
