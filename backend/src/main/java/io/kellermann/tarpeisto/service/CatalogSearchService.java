package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.repository.JdbcCatalogSearchRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogSearchService {
    public record ModelView(
            UUID id,
            String name,
            String trackingMode,
            UUID categoryId,
            String categoryName,
            String stockUnitLabel,
            boolean archived) {}

    public record ModelPage(List<ModelView> items, String nextCursor) {}

    public record StockView(
            UUID id,
            UUID assetModelId,
            String assetModelName,
            UUID categoryId,
            String categoryName,
            String stockUnitLabel,
            UUID containerAssetId,
            UUID locationId,
            String placePath,
            BigDecimal quantity,
            BigDecimal totalOnHand,
            BigDecimal lowStockThreshold,
            boolean lowStock,
            boolean archived,
            long version) {}

    public record StockPage(List<StockView> items, String nextCursor) {}

    private final JdbcCatalogSearchRepository search;

    public CatalogSearchService(JdbcCatalogSearchRepository search) {
        this.search = search;
    }

    @Transactional(readOnly = true)
    public ModelPage models(
            TarpeistoPrincipal p,
            String query,
            UUID category,
            TrackingMode mode,
            boolean archived,
            int limit,
            String cursor) {
        return models(p, query, category, mode, archived, limit, cursor, "name", "asc");
    }

    @Transactional(readOnly = true)
    public ModelPage models(
            TarpeistoPrincipal p,
            String query,
            UUID category,
            TrackingMode mode,
            boolean archived,
            int limit,
            String cursor,
            String sort,
            String direction) {
        if (!List.of("name", "category", "tracking").contains(sort))
            throw new ValidationFailedException("Unsupported model sort.");
        if (!List.of("asc", "desc").contains(direction))
            throw new ValidationFailedException("direction must be asc or desc.");
        boolean descending = "desc".equals(direction);
        require(p, limit);
        query = normalize(query);
        String filters = InventorySearchCursor.filters(
                "models", p.organizationId(), query, category, mode, archived, sort, direction);
        var anchor = InventorySearchCursor.parse(cursor, filters);
        var rows = search.models(
                p.organizationId(),
                query,
                category,
                mode == null ? null : mode.name(),
                archived,
                limit + 1,
                anchor == null ? null : anchor.anchor(),
                anchor == null ? null : anchor.id(),
                sort,
                descending);
        var items = rows.stream()
                .limit(limit)
                .map(r -> new ModelView(
                        r.id(),
                        r.name(),
                        r.trackingMode(),
                        r.categoryId(),
                        r.categoryName(),
                        r.stockUnitLabel(),
                        r.archived()))
                .toList();
        return new ModelPage(
                items,
                rows.size() > limit
                        ? InventorySearchCursor.encode(
                                rows.get(limit - 1).id(), rows.get(limit - 1).sortAnchor(), filters)
                        : null);
    }

    @Transactional(readOnly = true)
    public StockPage stocks(
            TarpeistoPrincipal p,
            String query,
            UUID category,
            UUID location,
            UUID container,
            boolean archived,
            Boolean lowStock,
            BigDecimal min,
            BigDecimal max,
            int limit,
            String cursor) {
        require(p, limit);
        query = normalize(query);
        if (min != null && min.signum() < 0
                || max != null && max.signum() < 0
                || min != null && max != null && min.compareTo(max) > 0)
            throw new ValidationFailedException("Select a valid quantity range.");
        String filters = InventorySearchCursor.filters(
                "stocks", p.organizationId(), query, category, location, container, archived, lowStock, min, max);
        var anchor = InventorySearchCursor.parse(cursor, filters);
        var rows = search.stocks(
                p.organizationId(),
                query,
                category,
                location,
                container,
                archived,
                lowStock,
                min,
                max,
                limit + 1,
                anchor == null ? null : anchor.anchor(),
                anchor == null ? null : anchor.id());
        var items = rows.stream()
                .limit(limit)
                .map(r -> new StockView(
                        r.id(),
                        r.assetModelId(),
                        r.assetModelName(),
                        r.categoryId(),
                        r.categoryName(),
                        r.stockUnitLabel(),
                        r.containerAssetId(),
                        r.locationId(),
                        r.placePath(),
                        r.quantity(),
                        r.totalOnHand(),
                        r.lowStockThreshold(),
                        r.lowStock(),
                        r.archived(),
                        r.version()))
                .toList();
        return new StockPage(
                items,
                rows.size() > limit
                        ? InventorySearchCursor.encode(
                                rows.get(limit - 1).cursorId(),
                                rows.get(limit - 1).sortAnchor(),
                                filters)
                        : null);
    }

    private static void require(TarpeistoPrincipal p, int limit) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
        p.requirePermanent();
        if (limit < 1 || limit > 100) throw new ValidationFailedException("limit must be between 1 and 100.");
    }

    private static String normalize(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.length() > 256) throw new ValidationFailedException("query is too long.");
        return s.trim();
    }
}
