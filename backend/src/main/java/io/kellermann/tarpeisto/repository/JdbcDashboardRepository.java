package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.DashboardQueue;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDashboardRepository {
    private final JdbcClient jdbc;

    public JdbcDashboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<DashboardRowProjection> page(UUID org, DashboardQueue queue, Instant now, int limit, UUID cursor) {
        return jdbc.sql("SELECT * FROM (" + query(queue)
                        + ") queue WHERE (CAST(:cursor AS uuid) IS NULL OR id>:cursor) ORDER BY id LIMIT :limit")
                .param("org", org)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("cursor", cursor, java.sql.Types.OTHER)
                .param("limit", limit)
                .query((r, i) -> new DashboardRowProjection(
                        r.getObject("id", UUID.class),
                        r.getString("label"),
                        r.getString("code"),
                        r.getString("state"),
                        r.getString("reason"),
                        r.getObject("target_id", UUID.class)))
                .list();
    }

    public long count(UUID org, DashboardQueue queue, Instant now) {
        return jdbc.sql("SELECT count(*) FROM (" + query(queue) + ") queue")
                .param("org", org)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    private static String query(DashboardQueue queue) {
        String asset = "SELECT a.id," + JdbcAssetSearchRepository.DISPLAY_NAME
                + " AS label,a.public_code AS code,a.lifecycle_state AS state,''::text AS reason,a.id AS target_id FROM physical_asset a JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id WHERE a.organization_id=:org AND a.archived_at IS NULL AND model.archived_at IS NULL AND a.lifecycle_state='ACTIVE'";
        return switch (queue) {
            case UPCOMING_EVENTS ->
                "SELECT b.id,b.name AS label,NULL::text AS code,b.status AS state,CASE WHEN b.reservation_status='ATTENTION_REQUIRED' THEN 'Reservation needs attention' ELSE 'Prepare equipment for checkout at '||b.starts_at::text END AS reason,b.id AS target_id FROM event_booking b WHERE b.organization_id=:org AND b.archived_at IS NULL AND b.status IN('DRAFT','RESERVED') AND b.ends_at>:now";
            case OUTSTANDING_CUSTODY ->
                "SELECT b.id,b.name AS label,NULL::text AS code,b.status AS state,'Equipment return or consumable accounting remains outstanding'::text AS reason,b.id AS target_id FROM event_booking b WHERE b.organization_id=:org AND b.archived_at IS NULL AND EXISTS(SELECT 1 FROM checkout_manifest m JOIN checkout_manifest_asset a ON a.checkout_manifest_id=m.id AND a.organization_id=m.organization_id WHERE m.organization_id=b.organization_id AND m.event_booking_id=b.id AND a.audit_released_at IS NULL) OR b.organization_id=:org AND b.archived_at IS NULL AND EXISTS(SELECT 1 FROM checkout_manifest m JOIN checkout_manifest_consumable c ON c.checkout_manifest_id=m.id AND c.organization_id=m.organization_id WHERE m.organization_id=b.organization_id AND m.event_booking_id=b.id AND c.semantics='SEPARATELY_ISSUED' AND c.accounted_at IS NULL)";
            case AUDITS ->
                "SELECT t.id," + JdbcAssetSearchRepository.DISPLAY_NAME
                        + " AS label,a.public_code AS code,coalesce(ca.state,t.state) AS state,CASE WHEN t.state='BLOCKED' THEN 'Complete child audits or review their findings first' WHEN ca.state='IN_PROGRESS' THEN 'Audit in progress' ELSE 'Audit ready to start' END AS reason,a.id AS target_id FROM audit_task t LEFT JOIN container_audit ca ON ca.audit_task_id=t.id AND ca.organization_id=t.organization_id AND ca.current_attempt JOIN physical_asset a ON a.id=t.container_asset_id AND a.organization_id=t.organization_id JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id JOIN audit_batch ab ON ab.id=t.audit_batch_id AND ab.organization_id=t.organization_id LEFT JOIN event_booking b ON b.id=ab.event_booking_id AND b.organization_id=t.organization_id WHERE t.organization_id=:org AND t.state<>'COMPLETED' AND b.archived_at IS NULL";
            case REVIEW ->
                "SELECT f.id," + JdbcAssetSearchRepository.DISPLAY_NAME
                        + " AS label,a.public_code AS code,f.finding_type AS state,coalesce(f.note,'Audit finding requires a decision') AS reason,ca.audit_task_id AS target_id FROM audit_finding f JOIN container_audit ca ON ca.id=f.container_audit_id AND ca.organization_id=f.organization_id JOIN physical_asset a ON a.id=ca.container_asset_id AND a.organization_id=ca.organization_id JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id WHERE f.organization_id=:org AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=f.organization_id AND r.audit_finding_id=f.id)";
            case REPAIRS ->
                "SELECT r.id," + JdbcAssetSearchRepository.DISPLAY_NAME
                        + " AS label,a.public_code AS code,'OPEN'::text AS state,r.reference_or_description AS reason,a.id AS target_id FROM asset_repair r JOIN physical_asset a ON a.id=r.asset_id AND a.organization_id=r.organization_id JOIN asset_model model ON model.id=a.asset_model_id AND model.organization_id=a.organization_id WHERE r.organization_id=:org AND r.closed_at IS NULL";
            case METADATA ->
                asset.replace("''::text AS reason", "'Missing values for active model fields'::text AS reason")
                        + " AND (" + JdbcAssetSearchRepository.METADATA_INCOMPLETE + ")";
            case CONTAINERS -> asset + " AND model.can_contain_assets";
            case LOW_STOCK ->
                "SELECT m.id,m.name AS label,NULL::text AS code,'LOW_STOCK'::text AS state,coalesce(sum(s.quantity),0)::text||' '||m.stock_unit_label||' on hand; threshold '||m.low_stock_threshold::text AS reason,m.id AS target_id FROM asset_model m LEFT JOIN consumable_stock_balance s ON s.asset_model_id=m.id AND s.organization_id=m.organization_id AND s.archived_at IS NULL WHERE m.organization_id=:org AND m.archived_at IS NULL AND m.tracking_mode='QUANTITY_STOCK' AND m.low_stock_threshold IS NOT NULL GROUP BY m.id HAVING coalesce(sum(s.quantity),0)<=m.low_stock_threshold";
        };
    }
}
