package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.AssetSearchFilter;
import io.kellermann.tarpeisto.model.Condition;
import io.kellermann.tarpeisto.model.LifecycleState;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Organization-scoped recursive place search with stable semantic keyset anchors. */
@Repository
public class JdbcAssetSearchRepository {
    public static final String DISPLAY_NAME = "coalesce(nullif(a.individual_name,''),model.name||' '||a.unit_number)";
    public static final String PATHS = """
        WITH RECURSIVE location_paths AS (
          SELECT id,parent_location_id,name::text AS path,ARRAY[id] AS ancestors FROM location WHERE organization_id=:org AND parent_location_id IS NULL
          UNION ALL SELECT l.id,l.parent_location_id,p.path||' / '||l.name,p.ancestors||l.id FROM location l JOIN location_paths p ON l.parent_location_id=p.id WHERE l.organization_id=:org),
        asset_paths AS (
          SELECT a.id,a.direct_location_id AS location_id,
            coalesce(nullif(a.individual_name,''),m.name||' '||a.unit_number)::text AS container_path
          FROM physical_asset a JOIN asset_model m ON m.id=a.asset_model_id AND m.organization_id=a.organization_id
          WHERE a.organization_id=:org AND a.parent_container_asset_id IS NULL
          UNION ALL SELECT a.id,p.location_id,p.container_path||' / '||coalesce(nullif(a.individual_name,''),m.name||' '||a.unit_number)
          FROM physical_asset a JOIN asset_paths p ON a.parent_container_asset_id=p.id
          JOIN asset_model m ON m.id=a.asset_model_id AND m.organization_id=a.organization_id WHERE a.organization_id=:org)
        """;
    public static final String METADATA_INCOMPLETE = """
        EXISTS(SELECT 1 FROM model_custom_field d WHERE d.organization_id=a.organization_id AND d.asset_model_id=a.asset_model_id AND d.archived_at IS NULL
          AND NOT EXISTS(SELECT 1 FROM asset_custom_field_value v WHERE v.organization_id=a.organization_id AND v.asset_id=a.id AND v.model_custom_field_id=d.id))
        """;
    private static final String FROM = """
        FROM physical_asset a JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id
        LEFT JOIN category category ON category.id=model.category_id AND category.organization_id=a.organization_id
        LEFT JOIN asset_paths path ON path.id=a.id LEFT JOIN location_paths location ON location.id=path.location_id
        """;
    private final JdbcClient jdbc;

    public JdbcAssetSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssetSearchProjection> search(
            UUID org,
            String query,
            UUID category,
            boolean defaultCategory,
            Boolean containerOnly,
            boolean includeInactive,
            String sort,
            boolean descending,
            int limit,
            UUID cursor) {
        String anchor = cursor == null
                ? null
                : jdbc.sql(
                                "SELECT " + order(sort)
                                        + " FROM physical_asset a JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id WHERE a.organization_id=:org AND a.id=:id")
                        .param("org", org)
                        .param("id", cursor)
                        .query(String.class)
                        .optional()
                        .orElseThrow(() -> new ValidationFailedException("cursor is invalid."));
        return search(
                org,
                query,
                category,
                defaultCategory,
                containerOnly,
                includeInactive,
                sort,
                descending,
                limit,
                cursor,
                anchor,
                AssetSearchFilter.defaults(includeInactive),
                Instant.EPOCH);
    }

    public List<AssetSearchProjection> search(
            UUID org,
            String query,
            UUID category,
            boolean defaultCategory,
            Boolean containerOnly,
            boolean includeInactive,
            String sort,
            boolean descending,
            int limit,
            UUID cursor,
            String anchor,
            AssetSearchFilter filter,
            Instant now) {
        String order = order(sort), comparison = descending ? "<" : ">";
        StringBuilder where = new StringBuilder(" WHERE a.organization_id=:org");
        if (!filter.includeArchived()) where.append(" AND a.archived_at IS NULL AND model.archived_at IS NULL");
        if (filter.lifecycle() != null) where.append(" AND a.lifecycle_state=:lifecycle");
        else if (!includeInactive) where.append(" AND a.lifecycle_state='ACTIVE'");
        if (query != null) where.append("""
            AND (a.public_code ILIKE :query OR a.unit_number::text ILIKE :query OR coalesce(a.individual_name,'') ILIKE :query OR model.name ILIKE :query
              OR path.container_path ILIKE :query OR location.path ILIKE :query OR EXISTS(SELECT 1 FROM asset_custom_field_value v JOIN model_custom_field d
                ON d.id=v.model_custom_field_id AND d.organization_id=v.organization_id WHERE v.organization_id=a.organization_id AND v.asset_id=a.id
                  AND d.archived_at IS NULL AND d.data_type='STRING' AND v.string_value ILIKE :query))
            """);
        if (category != null) where.append(" AND model.category_id=:category");
        if (defaultCategory) where.append(" AND model.category_id IS NULL");
        if (containerOnly != null) where.append(" AND model.can_contain_assets=:container");
        if (filter.locationId() != null) where.append(" AND :locationId=ANY(location.ancestors)");
        if (filter.condition() != null) where.append(" AND a.condition=:condition");
        if (filter.openRepair() != null)
            where.append(
                    " AND EXISTS(SELECT 1 FROM asset_repair r WHERE r.organization_id=a.organization_id AND r.asset_id=a.id AND r.closed_at IS NULL)=:openRepair");
        if (filter.metadataIncomplete() != null)
            where.append(" AND (").append(METADATA_INCOMPLETE).append(")=:metadataIncomplete");
        if (filter.bookingId() != null || filter.bookingStatus() != null) {
            where.append("""
                AND EXISTS(SELECT 1 FROM event_booking b WHERE b.organization_id=a.organization_id
                  AND (EXISTS(SELECT 1 FROM event_booking_line l WHERE l.organization_id=a.organization_id AND l.event_booking_id=b.id AND l.asset_id=a.id AND l.archived_at IS NULL)
                    OR EXISTS(SELECT 1 FROM event_booking_reservation_claim c WHERE c.organization_id=a.organization_id AND c.reservation_revision_id=b.current_revision_id AND c.asset_id=a.id)
                    OR EXISTS(SELECT 1 FROM checkout_manifest m JOIN checkout_manifest_asset ma ON ma.checkout_manifest_id=m.id AND ma.organization_id=m.organization_id
                      WHERE m.organization_id=a.organization_id AND m.event_booking_id=b.id AND ma.physical_asset_id=a.id))
                """);
            if (filter.bookingId() != null) where.append(" AND b.id=:bookingId");
            if (filter.bookingStatus() != null) where.append(" AND b.status=:bookingStatus");
            where.append(")");
        }
        if (filter.auditStatus() != null)
            where.append(
                    " AND EXISTS(SELECT 1 FROM audit_task t LEFT JOIN container_audit ca ON ca.audit_task_id=t.id AND ca.organization_id=t.organization_id AND ca.current_attempt LEFT JOIN container_audit_archive h ON h.container_audit_id=ca.id AND h.organization_id=ca.organization_id WHERE t.organization_id=a.organization_id AND t.container_asset_id=a.id AND h.archived_at IS NULL AND t.id=(SELECT latest.id FROM audit_task latest WHERE latest.organization_id=a.organization_id AND latest.container_asset_id=a.id ORDER BY latest.created_at DESC,latest.id DESC LIMIT 1) AND coalesce(ca.state,t.state)=:auditStatus)");
        if (filter.availability() != null)
            where.append(" AND (").append(AVAILABLE).append(")=:available");
        if (cursor != null)
            where.append(" AND (")
                    .append(order)
                    .append(comparison)
                    .append(":anchor OR (")
                    .append(order)
                    .append("=:anchor AND a.id ")
                    .append(comparison)
                    .append(" :cursor))");
        var statement = jdbc.sql(PATHS + "SELECT a.id," + DISPLAY_NAME
                        + " AS display_name,a.public_code,a.asset_model_id,model.name AS model_name,model.category_id,coalesce(category.name,'Default') AS category_name,coalesce(category.color,'#5B6472') AS category_color,model.can_contain_assets,a.condition,a.lifecycle_state,a.archived_at IS NOT NULL AS archived,a.parent_container_asset_id,a.version,"
                        + order
                        + " AS sort_anchor,path.location_id,coalesce(location.path||' / ','')||path.container_path AS place_path "
                        + FROM + where + " ORDER BY " + order + (descending ? " DESC" : " ASC") + ",a.id "
                        + (descending ? "DESC" : "ASC") + " LIMIT :limit")
                .param("org", org)
                .param("limit", limit);
        if (query != null) statement.param("query", like(query));
        if (category != null) statement.param("category", category);
        if (containerOnly != null) statement.param("container", containerOnly);
        if (cursor != null) statement.param("anchor", anchor).param("cursor", cursor);
        if (filter.locationId() != null) statement.param("locationId", filter.locationId());
        if (filter.condition() != null)
            statement.param("condition", filter.condition().name());
        if (filter.lifecycle() != null)
            statement.param("lifecycle", filter.lifecycle().name());
        if (filter.openRepair() != null) statement.param("openRepair", filter.openRepair());
        if (filter.metadataIncomplete() != null) statement.param("metadataIncomplete", filter.metadataIncomplete());
        if (filter.bookingId() != null) statement.param("bookingId", filter.bookingId());
        if (filter.bookingStatus() != null)
            statement.param("bookingStatus", filter.bookingStatus().name());
        if (filter.auditStatus() != null) statement.param("auditStatus", filter.auditStatus());
        if (filter.availability() != null)
            statement
                    .param("available", "AVAILABLE".equals(filter.availability()))
                    .param("now", now.atOffset(ZoneOffset.UTC));
        return statement
                .query((r, i) -> new AssetSearchProjection(
                        r.getObject("id", UUID.class),
                        r.getString("display_name"),
                        r.getString("public_code"),
                        r.getObject("asset_model_id", UUID.class),
                        r.getString("model_name"),
                        r.getObject("category_id", UUID.class),
                        r.getString("category_name"),
                        r.getString("category_color"),
                        r.getBoolean("can_contain_assets"),
                        Condition.valueOf(r.getString("condition")),
                        LifecycleState.valueOf(r.getString("lifecycle_state")),
                        r.getBoolean("archived"),
                        r.getObject("parent_container_asset_id", UUID.class),
                        r.getLong("version"),
                        r.getString("sort_anchor"),
                        r.getObject("location_id", UUID.class),
                        r.getString("place_path")))
                .list();
    }

    public static String like(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static String order(String sort) {
        return switch (sort) {
            case "name" -> "lower(" + DISPLAY_NAME + ")";
            case "code" -> "a.public_code";
            case "condition" -> "a.condition";
            case "lifecycle" -> "a.lifecycle_state";
            default -> throw new ValidationFailedException("Unsupported asset sort.");
        };
    }

    /** Current physical readiness, shared with inventory reporting; not a capacity forecast. */
    public static final String OPERATIONAL_STATE = """
        CASE WHEN a.archived_at IS NOT NULL OR model.archived_at IS NOT NULL THEN 'ARCHIVED'
        WHEN a.lifecycle_state<>'ACTIVE' THEN a.lifecycle_state
        ELSE (WITH RECURSIVE ancestors AS (
          SELECT a.id,a.parent_container_asset_id UNION SELECT p.id,p.parent_container_asset_id FROM physical_asset p JOIN ancestors c ON p.id=c.parent_container_asset_id WHERE p.organization_id=a.organization_id),
          descendants AS (SELECT a.id UNION SELECT p.id FROM physical_asset p JOIN descendants c ON p.parent_container_asset_id=c.id
            OR EXISTS(SELECT 1 FROM packing_requirement pin WHERE pin.organization_id=a.organization_id AND pin.container_asset_id=c.id AND pin.specific_asset_id=p.id AND pin.archived_at IS NULL) WHERE p.organization_id=a.organization_id),
          related AS (SELECT id FROM ancestors UNION SELECT id FROM descendants)
          SELECT CASE
            WHEN EXISTS(SELECT 1 FROM checkout_manifest_asset c WHERE c.organization_id=a.organization_id AND c.physical_asset_id IN(SELECT id FROM related) AND c.audit_released_at IS NULL) THEN 'EVENT_CUSTODY'
            WHEN EXISTS(SELECT 1 FROM asset_repair r WHERE r.organization_id=a.organization_id AND r.asset_id IN(SELECT id FROM related) AND r.closed_at IS NULL) THEN 'REPAIR'
            WHEN EXISTS(SELECT 1 FROM audit_task t JOIN container_audit ca ON ca.audit_task_id=t.id AND ca.organization_id=t.organization_id AND ca.current_attempt WHERE t.organization_id=a.organization_id AND t.container_asset_id IN(SELECT id FROM related) AND ca.state='IN_PROGRESS') THEN 'AUDIT_IN_PROGRESS'
            WHEN EXISTS(SELECT 1 FROM audit_task t WHERE t.organization_id=a.organization_id AND t.container_asset_id IN(SELECT id FROM related) AND t.state<>'COMPLETED') THEN 'AUDIT_PENDING'
            WHEN EXISTS(SELECT 1 FROM container_audit ca JOIN audit_finding f ON f.container_audit_id=ca.id AND f.organization_id=ca.organization_id WHERE ca.organization_id=a.organization_id AND ca.container_asset_id IN(SELECT id FROM related) AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=a.organization_id AND r.audit_finding_id=f.id)) THEN 'REVIEW_REQUIRED'
            WHEN EXISTS(SELECT 1 FROM event_booking_reservation_claim c JOIN event_booking b ON b.current_revision_id=c.reservation_revision_id AND b.organization_id=c.organization_id WHERE c.organization_id=a.organization_id AND c.asset_id IN(SELECT id FROM related) AND b.status='RESERVED' AND b.starts_at<=:now AND b.ends_at>:now) THEN 'RESERVED'
            ELSE 'ON_HAND' END) END
        """;

    private static final String AVAILABLE = """
        a.archived_at IS NULL AND model.archived_at IS NULL AND a.lifecycle_state='ACTIVE'
        AND NOT EXISTS(WITH RECURSIVE ancestors AS (
          SELECT a.id,a.parent_container_asset_id UNION SELECT p.id,p.parent_container_asset_id FROM physical_asset p JOIN ancestors c ON p.id=c.parent_container_asset_id WHERE p.organization_id=a.organization_id),
          descendants AS (SELECT a.id UNION SELECT p.id FROM physical_asset p JOIN descendants c ON p.parent_container_asset_id=c.id
            OR EXISTS(SELECT 1 FROM packing_requirement pin WHERE pin.organization_id=a.organization_id AND pin.container_asset_id=c.id AND pin.specific_asset_id=p.id AND pin.archived_at IS NULL) WHERE p.organization_id=a.organization_id),
          related AS (SELECT id FROM ancestors UNION SELECT id FROM descendants)
          SELECT 1 FROM related x WHERE EXISTS(SELECT 1 FROM checkout_manifest_asset c WHERE c.organization_id=a.organization_id AND c.physical_asset_id=x.id AND c.audit_released_at IS NULL)
            OR EXISTS(SELECT 1 FROM asset_repair r WHERE r.organization_id=a.organization_id AND r.asset_id=x.id AND r.closed_at IS NULL)
            OR EXISTS(SELECT 1 FROM audit_task t WHERE t.organization_id=a.organization_id AND t.container_asset_id=x.id AND t.state<>'COMPLETED')
            OR EXISTS(SELECT 1 FROM container_audit ca JOIN audit_finding f ON f.container_audit_id=ca.id AND f.organization_id=ca.organization_id WHERE ca.organization_id=a.organization_id AND ca.container_asset_id=x.id AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=a.organization_id AND r.audit_finding_id=f.id))
            OR EXISTS(SELECT 1 FROM event_booking_reservation_claim c JOIN event_booking b ON b.current_revision_id=c.reservation_revision_id AND b.organization_id=c.organization_id
              WHERE c.organization_id=a.organization_id AND c.asset_id=x.id AND b.status='RESERVED' AND b.starts_at<=:now AND b.ends_at>:now))
        """;
}
