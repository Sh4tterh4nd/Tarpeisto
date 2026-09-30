package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.CatalogSearchService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogSearchController {
    private final CatalogSearchService search;

    public CatalogSearchController(CatalogSearchService search) {
        this.search = search;
    }

    @GetMapping("/api/v1/asset-models/search")
    public ModelSearchPageResponse searchModels(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UUID category,
            @RequestParam(required = false) TrackingMode trackingMode,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction) {
        return ModelSearchPageResponse.from(
                search.models(p, query, category, trackingMode, includeArchived, limit, cursor, sort, direction));
    }

    @GetMapping("/api/v1/consumable-stock/search")
    public StockSearchPageResponse searchStock(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) UUID category,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID containerId,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) Boolean lowStock,
            @RequestParam(required = false) BigDecimal minimumQuantity,
            @RequestParam(required = false) BigDecimal maximumQuantity,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String cursor) {
        return StockSearchPageResponse.from(search.stocks(
                p,
                query,
                category,
                locationId,
                containerId,
                includeArchived,
                lowStock,
                minimumQuantity,
                maximumQuantity,
                limit,
                cursor));
    }
}
