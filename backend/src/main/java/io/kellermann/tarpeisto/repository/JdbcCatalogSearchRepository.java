package io.kellermann.tarpeisto.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCatalogSearchRepository {
    private final JdbcClient jdbc;

    public JdbcCatalogSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CatalogSearchProjection> models(
            UUID org,
            String query,
            UUID category,
            String mode,
            boolean archived,
            int limit,
            String anchor,
            UUID cursor,
            String sort,
            boolean descending) {
        String order = switch (sort) {
            case "name" -> "lower(m.name)";
            case "category" -> "lower(coalesce(c.name,'Default'))";
            case "tracking" -> "m.tracking_mode";
            default -> throw new io.kellermann.tarpeisto.exception.ValidationFailedException("Unsupported model sort.");
        };
        String comparison = descending ? "<" : ">";
        String where = " WHERE m.organization_id=:org" + (archived ? "" : " AND m.archived_at IS NULL")
                + (query == null ? "" : " AND (m.name ILIKE :query OR coalesce(c.name,'Default') ILIKE :query)")
                + (category == null ? "" : " AND m.category_id=:category")
                + (mode == null ? "" : " AND m.tracking_mode=:mode")
                + (cursor == null
                        ? ""
                        : " AND (" + order + comparison + ":anchor OR (" + order + "=:anchor AND m.id" + comparison
                                + ":cursor))");
        var q = jdbc.sql(
                        "SELECT m.id,m.name,m.tracking_mode,m.category_id,coalesce(c.name,'Default') AS category_name,m.stock_unit_label,m.archived_at IS NOT NULL AS archived,"
                                + order
                                + " AS sort_anchor FROM asset_model m LEFT JOIN category c ON c.id=m.category_id AND c.organization_id=m.organization_id"
                                + where + " ORDER BY " + order + (descending ? " DESC" : " ASC") + ",m.id "
                                + (descending ? "DESC" : "ASC") + " LIMIT :limit")
                .param("org", org)
                .param("limit", limit);
        if (query != null) q.param("query", JdbcAssetSearchRepository.like(query));
        if (category != null) q.param("category", category);
        if (mode != null) q.param("mode", mode);
        if (cursor != null) q.param("anchor", anchor).param("cursor", cursor);
        return q.query((r, i) -> new CatalogSearchProjection(
                        r.getObject("id", UUID.class),
                        r.getString("name"),
                        r.getString("tracking_mode"),
                        r.getObject("category_id", UUID.class),
                        r.getString("category_name"),
                        r.getString("stock_unit_label"),
                        r.getBoolean("archived"),
                        r.getString("sort_anchor")))
                .list();
    }

    public List<StockSearchProjection> stocks(
            UUID org,
            String query,
            UUID category,
            UUID location,
            UUID container,
            boolean archived,
            Boolean lowStock,
            BigDecimal min,
            BigDecimal max,
            int limit,
            String anchor,
            UUID cursor) {
        String place = "coalesce(lp.path,coalesce(cl.path||' / ','')||ap.container_path,'No stock place')";
        String sort = "lower(m.name||' / '||" + place + ")";
        String where = " WHERE m.organization_id=:org AND m.tracking_mode='QUANTITY_STOCK'"
                + (archived ? "" : " AND m.archived_at IS NULL")
                + (query == null
                        ? ""
                        : " AND (m.name ILIKE :query OR coalesce(c.name,'Default') ILIKE :query OR m.stock_unit_label ILIKE :query OR "
                                + place + " ILIKE :query OR a.public_code ILIKE :query)")
                + (category == null ? "" : " AND m.category_id=:category")
                + (location == null ? "" : " AND (:location=ANY(lp.ancestors) OR :location=ANY(cl.ancestors))")
                + (container == null ? "" : " AND s.container_asset_id=:container")
                + (lowStock == null
                        ? ""
                        : " AND (m.low_stock_threshold IS NOT NULL AND coalesce(t.total,0)<=m.low_stock_threshold)=:low")
                + (min == null ? "" : " AND coalesce(s.quantity,0)>=:min")
                + (max == null ? "" : " AND coalesce(s.quantity,0)<=:max")
                + (cursor == null
                        ? ""
                        : " AND (" + sort + ">:anchor OR (" + sort + "=:anchor AND coalesce(s.id,m.id)>:cursor))");
        var q = jdbc.sql(JdbcAssetSearchRepository.PATHS
                        + ", totals AS (SELECT asset_model_id,sum(quantity) AS total FROM consumable_stock_balance WHERE organization_id=:org AND archived_at IS NULL GROUP BY asset_model_id) "
                        + "SELECT s.id,m.id AS model_id,m.name,m.category_id,coalesce(c.name,'Default') AS category_name,m.stock_unit_label,s.container_asset_id,coalesce(s.location_id,ap.location_id) AS location_id,"
                        + place
                        + " AS place_path,coalesce(s.quantity,0) AS quantity,coalesce(t.total,0) AS total,m.low_stock_threshold,m.low_stock_threshold IS NOT NULL AND coalesce(t.total,0)<=m.low_stock_threshold AS low_stock,s.archived_at IS NOT NULL AS archived,coalesce(s.version,0) AS version,"
                        + sort
                        + " AS sort_anchor,coalesce(s.id,m.id) AS cursor_id FROM asset_model m LEFT JOIN category c ON c.id=m.category_id AND c.organization_id=m.organization_id LEFT JOIN consumable_stock_balance s ON s.asset_model_id=m.id AND s.organization_id=m.organization_id "
                        + (archived ? "" : " AND s.archived_at IS NULL")
                        + " LEFT JOIN physical_asset a ON a.id=s.container_asset_id AND a.organization_id=m.organization_id LEFT JOIN asset_paths ap ON ap.id=s.container_asset_id LEFT JOIN location_paths lp ON lp.id=s.location_id LEFT JOIN location_paths cl ON cl.id=ap.location_id LEFT JOIN totals t ON t.asset_model_id=m.id"
                        + where + " ORDER BY " + sort + ",coalesce(s.id,m.id) LIMIT :limit")
                .param("org", org)
                .param("limit", limit);
        if (query != null) q.param("query", JdbcAssetSearchRepository.like(query));
        if (category != null) q.param("category", category);
        if (location != null) q.param("location", location);
        if (container != null) q.param("container", container);
        if (lowStock != null) q.param("low", lowStock);
        if (min != null) q.param("min", min);
        if (max != null) q.param("max", max);
        if (cursor != null) q.param("anchor", anchor).param("cursor", cursor);
        return q.query((r, i) -> new StockSearchProjection(
                        r.getObject("id", UUID.class),
                        r.getObject("model_id", UUID.class),
                        r.getString("name"),
                        r.getObject("category_id", UUID.class),
                        r.getString("category_name"),
                        r.getString("stock_unit_label"),
                        r.getObject("container_asset_id", UUID.class),
                        r.getObject("location_id", UUID.class),
                        r.getString("place_path"),
                        r.getBigDecimal("quantity"),
                        r.getBigDecimal("total"),
                        r.getBigDecimal("low_stock_threshold"),
                        r.getBoolean("low_stock"),
                        r.getBoolean("archived"),
                        r.getLong("version"),
                        r.getString("sort_anchor"),
                        r.getObject("cursor_id", UUID.class)))
                .list();
    }
}
