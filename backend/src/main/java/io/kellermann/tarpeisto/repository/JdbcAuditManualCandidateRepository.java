package io.kellermann.tarpeisto.repository;

import java.sql.Types;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Candidates belong to one physical audit boundary, never the organization's interchangeable pool. */
@Repository
public class JdbcAuditManualCandidateRepository {
    private final JdbcClient jdbc;

    public JdbcAuditManualCandidateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SCOPE = """
            WITH candidate_ids AS (
              SELECT a.id FROM physical_asset a JOIN container_audit audit ON audit.container_asset_id=a.parent_container_asset_id
                AND audit.organization_id=a.organization_id WHERE audit.organization_id=:org AND audit.id=:audit
              UNION SELECT e.specific_asset_id FROM audit_expected_requirement e
                WHERE e.organization_id=:org AND e.container_audit_id=:audit AND e.specific_asset_id IS NOT NULL
              UNION SELECT s.physical_asset_id FROM audit_scan s
                WHERE s.organization_id=:org AND s.container_audit_id=:audit AND s.undone_at IS NULL
            )
            """;

    public boolean contains(UUID org, UUID audit, UUID asset) {
        return jdbc.sql(SCOPE + "SELECT EXISTS(SELECT 1 FROM candidate_ids WHERE id=:asset)")
                .param("org", org)
                .param("audit", audit)
                .param("asset", asset)
                .query(Boolean.class)
                .single();
    }

    public List<Candidate> page(UUID org, UUID audit, String query, UUID after, int limit) {
        String pattern = "%" + query.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return jdbc.sql(SCOPE + """
            SELECT a.id, coalesce(nullif(trim(a.individual_name),''),m.name||' '||a.unit_number) AS display_name,
              a.public_code,a.asset_model_id,m.name AS model_name,
              (a.archived_at IS NULL AND a.lifecycle_state='ACTIVE') AS active
            FROM candidate_ids c JOIN physical_asset a ON a.id=c.id AND a.organization_id=:org
            JOIN asset_model m ON m.id=a.asset_model_id AND m.organization_id=a.organization_id
            WHERE (CAST(:after AS uuid) IS NULL OR a.id>CAST(:after AS uuid))
              AND (a.public_code ILIKE :query ESCAPE '!' OR a.individual_name ILIKE :query ESCAPE '!' OR m.name ILIKE :query ESCAPE '!')
            ORDER BY a.id LIMIT :limit
            """)
                .param("org", org)
                .param("audit", audit)
                .param("query", pattern)
                .param("after", after, Types.OTHER)
                .param("limit", limit)
                .query((r, n) -> new Candidate(
                        r.getObject("id", UUID.class),
                        r.getString("display_name"),
                        r.getString("public_code"),
                        r.getObject("asset_model_id", UUID.class),
                        r.getString("model_name"),
                        r.getBoolean("active")))
                .list();
    }

    public record Candidate(
            UUID id, String displayName, String publicCode, UUID assetModelId, String modelName, boolean active) {}
}
