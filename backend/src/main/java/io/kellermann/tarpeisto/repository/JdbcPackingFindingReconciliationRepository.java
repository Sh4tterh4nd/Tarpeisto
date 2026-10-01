package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.AuditFindingType;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Bounded append-only finding candidates, including retired completed audit attempts. */
@Repository
public class JdbcPackingFindingReconciliationRepository {
    private final JdbcClient jdbc;

    public JdbcPackingFindingReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Candidate> page(UUID organizationId, UUID containerId, UUID after, int limit) {
        return jdbc.sql("""
                SELECT f.id, f.container_audit_id, a.audit_batch_id, a.container_asset_id,
                       f.physical_asset_id, f.finding_type, f.detail::text, f.source_operation_id
                FROM audit_finding f
                JOIN container_audit a ON a.organization_id=f.organization_id AND a.id=f.container_audit_id
                WHERE f.organization_id=:org AND a.state='COMPLETED'
                  AND f.finding_type IN ('MISSING','UNEXPECTED','MISPLACED')
                  AND (CAST(:container AS uuid) IS NULL OR a.container_asset_id=:container)
                  AND (CAST(:after AS uuid) IS NULL OR f.id>:after)
                  AND NOT EXISTS (SELECT 1 FROM audit_finding_resolution r
                      WHERE r.organization_id=f.organization_id AND r.audit_finding_id=f.id)
                ORDER BY f.id LIMIT :limit
                """)
                .param("org", organizationId, Types.OTHER)
                .param("container", containerId, Types.OTHER)
                .param("after", after, Types.OTHER)
                .param("limit", limit)
                .query((rs, row) -> new Candidate(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class),
                        AuditFindingType.valueOf(rs.getString(6)),
                        rs.getString(7),
                        rs.getObject(8, UUID.class)))
                .list();
    }

    public boolean eligibleSource(UUID organizationId, UUID containerId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM physical_asset a JOIN asset_model m
                    ON m.organization_id=a.organization_id AND m.id=a.asset_model_id
                    WHERE a.organization_id=:org AND a.id=:container
                      AND a.archived_at IS NULL AND a.lifecycle_state='ACTIVE'
                      AND m.archived_at IS NULL AND m.can_contain_assets)
                """)
                .param("org", organizationId, Types.OTHER)
                .param("container", containerId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    public boolean inProgress(UUID organizationId, UUID containerId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM container_audit
                    WHERE organization_id=:org AND container_asset_id=:container
                      AND current_attempt AND state='IN_PROGRESS')
                """)
                .param("org", organizationId, Types.OTHER)
                .param("container", containerId, Types.OTHER)
                .query(Boolean.class)
                .single();
    }

    public record Candidate(
            UUID findingId,
            UUID auditId,
            UUID batchId,
            UUID containerId,
            UUID assetId,
            AuditFindingType type,
            String detail,
            UUID sourceOperationId) {}
}
