package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Condition;
import io.kellermann.tarpeisto.model.LifecycleState;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bounded keyset search with semantic ordering and a UUID tie breaker. */
@Repository
public class JdbcAssetSearchRepository {
    private static final String DISPLAY_NAME =
            "coalesce(nullif(a.individual_name, ''), model.name || ' ' || a.unit_number)";
    private static final String FROM = """
            FROM physical_asset a
            JOIN asset_model model ON model.id = a.asset_model_id AND model.organization_id = a.organization_id
            LEFT JOIN category category ON category.id = model.category_id AND category.organization_id = a.organization_id
            """;
    private final JdbcClient jdbc;

    public JdbcAssetSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssetSearchProjection> search(
            UUID organizationId,
            String query,
            UUID categoryId,
            boolean defaultCategory,
            Boolean containerOnly,
            boolean includeInactive,
            String sort,
            boolean descending,
            int limit,
            UUID cursor) {
        String order = switch (sort) {
            case "name" -> "lower(" + DISPLAY_NAME + ")";
            case "code" -> "a.public_code";
            case "condition" -> "a.condition";
            case "lifecycle" -> "a.lifecycle_state";
            default -> throw new ValidationFailedException("Unsupported asset sort.");
        };
        String anchorValue = null;
        if (cursor != null) {
            anchorValue = jdbc.sql(
                            "SELECT " + order + " " + FROM + " WHERE a.organization_id = :org AND a.id = :cursor")
                    .param("org", organizationId)
                    .param("cursor", cursor)
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new ValidationFailedException("cursor is invalid."));
        }
        StringBuilder where = new StringBuilder(" WHERE a.organization_id = :org");
        if (!includeInactive) where.append(" AND a.archived_at IS NULL AND a.lifecycle_state = 'ACTIVE'");
        if (query != null)
            where.append(
                    " AND (a.public_code ILIKE :query OR coalesce(a.individual_name, '') ILIKE :query OR model.name ILIKE :query)");
        if (categoryId != null) where.append(" AND model.category_id = :category");
        if (defaultCategory) where.append(" AND model.category_id IS NULL");
        if (containerOnly != null) where.append(" AND model.can_contain_assets = :container");
        if (cursor != null)
            where.append(" AND (")
                    .append(order)
                    .append(descending ? " < :anchor" : " > :anchor")
                    .append(" OR (")
                    .append(order)
                    .append(" = :anchor AND a.id > :cursor))");
        var statement = jdbc.sql("SELECT a.id, " + DISPLAY_NAME + " AS display_name, a.public_code, a.asset_model_id,"
                        + " model.name AS model_name, model.category_id, coalesce(category.name, 'Default') AS category_name,"
                        + " coalesce(category.color, '#5B6472') AS category_color, model.can_contain_assets,"
                        + " a.condition, a.lifecycle_state, a.archived_at IS NOT NULL AS archived,"
                        + " a.parent_container_asset_id, a.version " + FROM + where
                        + " ORDER BY " + order + (descending ? " DESC" : " ASC") + ", a.id ASC LIMIT :limit")
                .param("org", organizationId)
                .param("limit", limit);
        if (query != null)
            statement.param(
                    "query",
                    "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        if (categoryId != null) statement.param("category", categoryId);
        if (containerOnly != null) statement.param("container", containerOnly);
        if (cursor != null) statement.param("anchor", anchorValue).param("cursor", cursor);
        return statement
                .query((result, index) -> new AssetSearchProjection(
                        result.getObject("id", UUID.class),
                        result.getString("display_name"),
                        result.getString("public_code"),
                        result.getObject("asset_model_id", UUID.class),
                        result.getString("model_name"),
                        result.getObject("category_id", UUID.class),
                        result.getString("category_name"),
                        result.getString("category_color"),
                        result.getBoolean("can_contain_assets"),
                        Condition.valueOf(result.getString("condition")),
                        LifecycleState.valueOf(result.getString("lifecycle_state")),
                        result.getBoolean("archived"),
                        result.getObject("parent_container_asset_id", UUID.class),
                        result.getLong("version")))
                .list();
    }
}
