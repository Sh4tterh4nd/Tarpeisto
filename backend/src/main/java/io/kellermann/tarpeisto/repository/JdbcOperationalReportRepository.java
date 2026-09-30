package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.ReportKind;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Fixed report projections read a complete snapshot in bounded semantic-key pages. */
@Repository
public class JdbcOperationalReportRepository {
    public record Row(String cursor, List<Object> cells) {}

    public record Definition(List<String> headers, String prefix, String sql) {}

    private final JdbcClient jdbc;

    public JdbcOperationalReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean auditExists(UUID org, UUID audit) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM container_audit WHERE organization_id=:org AND id=:id)")
                .param("org", org)
                .param("id", audit)
                .query(Boolean.class)
                .single();
    }

    public List<Row> page(UUID org, ReportKind kind, UUID audit, String after, int limit, java.time.Instant now) {
        Definition d = definition(kind);
        var q = jdbc.sql(
                        d.prefix() + " SELECT * FROM (" + d.sql()
                                + ") report WHERE (CAST(:after AS text) IS NULL OR sort_key>:after) ORDER BY sort_key LIMIT :limit")
                .param("org", org)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("after", after, java.sql.Types.VARCHAR)
                .param("limit", limit);
        if (kind == ReportKind.AUDITS) q.param("audit", audit, java.sql.Types.OTHER);
        return q.query((r, i) -> {
                    List<Object> cells = new ArrayList<>(d.headers().size());
                    for (String column : d.headers()) {
                        Object value = r.getObject(column);
                        if (value instanceof java.sql.Timestamp at)
                            value = at.toInstant().atOffset(java.time.ZoneOffset.UTC);
                        cells.add(value);
                    }
                    return new Row(r.getString("sort_key"), java.util.Collections.unmodifiableList(cells));
                })
                .list();
    }

    public Definition definition(ReportKind kind) {
        return switch (kind) {
            case INVENTORY ->
                new Definition(
                        List.of(
                                "record_type",
                                "model_id",
                                "model_name",
                                "tracking_mode",
                                "category",
                                "model_archived",
                                "asset_id",
                                "asset_name",
                                "public_code",
                                "unit_number",
                                "condition",
                                "lifecycle",
                                "asset_archived",
                                "place_path",
                                "active_custom_values",
                                "operational_state"),
                        JdbcAssetSearchRepository.PATHS,
                        """
                SELECT 'M'||m.id::text AS sort_key,'MODEL'::text AS record_type,m.id AS model_id,m.name AS model_name,m.tracking_mode,coalesce(c.name,'Default') AS category,m.archived_at IS NOT NULL AS model_archived,
                  NULL::uuid AS asset_id,NULL::text AS asset_name,NULL::text AS public_code,NULL::integer AS unit_number,NULL::text AS condition,NULL::text AS lifecycle,NULL::boolean AS asset_archived,NULL::text AS place_path,NULL::text AS active_custom_values,NULL::text AS operational_state
                FROM asset_model m LEFT JOIN category c ON c.id=m.category_id AND c.organization_id=m.organization_id WHERE m.organization_id=:org
                UNION ALL
                SELECT 'A'||a.id::text,'ASSET',model.id,model.name,model.tracking_mode,coalesce(c.name,'Default'),model.archived_at IS NOT NULL,a.id,coalesce(nullif(a.individual_name,''),model.name||' '||a.unit_number),a.public_code,a.unit_number,a.condition,a.lifecycle_state,a.archived_at IS NOT NULL,
                  coalesce(l.path||' / ','')||p.container_path,
                  coalesce((SELECT jsonb_object_agg(d.name,coalesce(v.string_value,v.date_value::text,o.value))::text FROM model_custom_field d JOIN asset_custom_field_value v ON v.model_custom_field_id=d.id AND v.organization_id=d.organization_id AND v.asset_id=a.id LEFT JOIN model_custom_field_option o ON o.id=v.option_id AND o.organization_id=v.organization_id WHERE d.organization_id=:org AND d.archived_at IS NULL),'{}'),
                  """ + JdbcAssetSearchRepository.OPERATIONAL_STATE + """
                FROM physical_asset a JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id LEFT JOIN category c ON c.id=model.category_id AND c.organization_id=a.organization_id LEFT JOIN asset_paths p ON p.id=a.id LEFT JOIN location_paths l ON l.id=p.location_id WHERE a.organization_id=:org
                """);
            case CONSUMABLE_BALANCES ->
                new Definition(
                        List.of(
                                "balance_id",
                                "model_id",
                                "model_name",
                                "category",
                                "stock_unit",
                                "container_id",
                                "location_id",
                                "place_path",
                                "quantity",
                                "archived",
                                "version",
                                "low_stock_threshold",
                                "active_on_hand",
                                "created_at",
                                "updated_at"),
                        JdbcAssetSearchRepository.PATHS,
                        """
                SELECT s.id::text AS sort_key,s.id AS balance_id,m.id AS model_id,m.name AS model_name,coalesce(c.name,'Default') AS category,m.stock_unit_label AS stock_unit,s.container_asset_id AS container_id,coalesce(s.location_id,p.location_id) AS location_id,
                  coalesce(d.path,coalesce(l.path||' / ','')||p.container_path) AS place_path,s.quantity,s.archived_at IS NOT NULL AS archived,s.version,m.low_stock_threshold,
                  coalesce((SELECT sum(b.quantity) FROM consumable_stock_balance b WHERE b.organization_id=:org AND b.asset_model_id=m.id AND b.archived_at IS NULL),0) AS active_on_hand,s.created_at,s.updated_at
                FROM consumable_stock_balance s JOIN asset_model m ON m.id=s.asset_model_id AND m.organization_id=s.organization_id LEFT JOIN category c ON c.id=m.category_id AND c.organization_id=m.organization_id LEFT JOIN asset_paths p ON p.id=s.container_asset_id LEFT JOIN location_paths l ON l.id=p.location_id LEFT JOIN location_paths d ON d.id=s.location_id WHERE s.organization_id=:org
                """);
            case STOCK_MOVEMENTS ->
                new Definition(
                        List.of(
                                "movement_id",
                                "balance_id",
                                "model_id",
                                "model_name",
                                "balance_archived",
                                "quantity_delta",
                                "resulting_quantity",
                                "stock_unit",
                                "reason",
                                "actor_user_id",
                                "actor_display_name",
                                "note",
                                "transfer_group_id",
                                "event_reference_id",
                                "audit_reference_id",
                                "occurred_at"),
                        "",
                        """
                SELECT x.id::text AS sort_key,x.id AS movement_id,s.id AS balance_id,m.id AS model_id,m.name AS model_name,s.archived_at IS NOT NULL AS balance_archived,x.quantity_delta,x.resulting_quantity,x.stock_unit_label AS stock_unit,x.reason,x.actor_user_id,u.display_name AS actor_display_name,x.note,x.transfer_group_id,x.event_reference_id,x.audit_reference_id,x.occurred_at
                FROM stock_movement x JOIN consumable_stock_balance s ON s.id=x.consumable_stock_balance_id AND s.organization_id=x.organization_id JOIN asset_model m ON m.id=s.asset_model_id AND m.organization_id=s.organization_id LEFT JOIN app_user u ON u.id=x.actor_user_id WHERE x.organization_id=:org
                """);
            case AUDITS -> auditDefinition();
        };
    }

    private static Definition auditDefinition() {
        String prefix = """
            WITH selected AS (SELECT a.*,h.archived_at IS NOT NULL AS archived FROM container_audit a LEFT JOIN container_audit_archive h ON h.container_audit_id=a.id AND h.organization_id=a.organization_id WHERE a.organization_id=:org AND a.state='COMPLETED' AND (CAST(:audit AS uuid) IS NULL OR a.id=:audit)),
            facts AS (
              SELECT 'AUDIT'::text AS record_type,a.id AS record_id,a.id AS audit_id,a.completed_by_user_id AS actor_user_id,a.completed_at AS occurred_at,NULL::numeric AS quantity,a.state AS state,to_jsonb(a)::text AS detail FROM selected a
              UNION ALL SELECT 'EXPECTED',r.id,a.id,NULL::uuid,a.started_at,r.required_quantity,r.requirement_type,to_jsonb(r)::text FROM selected a JOIN audit_expected_requirement r ON r.container_audit_id=a.id AND r.organization_id=a.organization_id
              UNION ALL SELECT 'SCAN',s.id,a.id,s.scanned_by_user_id,s.scanned_at,NULL::numeric,s.outcome,to_jsonb(s)::text FROM selected a JOIN audit_scan s ON s.container_audit_id=a.id AND s.organization_id=a.organization_id
              UNION ALL SELECT 'UNDO',s.id,a.id,s.undone_by_user_id,s.undone_at,NULL::numeric,'UNDONE',to_jsonb(s)::text FROM selected a JOIN audit_scan s ON s.container_audit_id=a.id AND s.organization_id=a.organization_id WHERE s.undone_at IS NOT NULL
              UNION ALL SELECT 'CONSUMABLE',c.id,a.id,c.recorded_by_user_id,c.recorded_at,c.observed_quantity,c.status,to_jsonb(c)::text FROM selected a JOIN audit_consumable_observation c ON c.container_audit_id=a.id AND c.organization_id=a.organization_id
              UNION ALL SELECT 'FINDING',f.id,a.id,f.recorded_by_user_id,f.recorded_at,NULL::numeric,f.finding_type,to_jsonb(f)::text FROM selected a JOIN audit_finding f ON f.container_audit_id=a.id AND f.organization_id=a.organization_id
              UNION ALL SELECT 'OPERATION',o.id,a.id,NULL::uuid,o.recorded_at,NULL::numeric,o.action,(to_jsonb(o)-'fingerprint')::text FROM selected a JOIN audit_operation o ON o.container_audit_id=a.id AND o.organization_id=a.organization_id
              UNION ALL SELECT 'RESOLUTION',r.id,a.id,r.actor_user_id,r.resolved_at,NULL::numeric,r.action,to_jsonb(r)::text FROM selected a JOIN audit_finding f ON f.container_audit_id=a.id AND f.organization_id=a.organization_id JOIN audit_finding_resolution r ON r.audit_finding_id=f.id AND r.organization_id=f.organization_id)
            """;
        return new Definition(
                List.of(
                        "record_type",
                        "record_id",
                        "audit_id",
                        "task_id",
                        "batch_id",
                        "container_id",
                        "archived",
                        "actor_user_id",
                        "actor_display_name",
                        "occurred_at",
                        "quantity",
                        "state",
                        "detail"),
                prefix,
                """
            SELECT f.record_type||f.record_id::text AS sort_key,f.record_type,f.record_id,f.audit_id,a.audit_task_id AS task_id,a.audit_batch_id AS batch_id,a.container_asset_id AS container_id,a.archived,f.actor_user_id,u.display_name AS actor_display_name,f.occurred_at,f.quantity,f.state,f.detail FROM facts f JOIN selected a ON a.id=f.audit_id LEFT JOIN app_user u ON u.id=f.actor_user_id
            """);
    }
}
