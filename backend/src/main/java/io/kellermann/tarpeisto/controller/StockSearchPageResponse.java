package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.service.CatalogSearchService;
import java.util.List;

public record StockSearchPageResponse(List<StockSearchResponse> items, String nextCursor) {
    static StockSearchPageResponse from(CatalogSearchService.StockPage p) {
        return new StockSearchPageResponse(
                p.items().stream().map(StockSearchResponse::from).toList(), p.nextCursor());
    }
}
