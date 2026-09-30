package io.kellermann.tarpeisto.repository;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Actor names are available only through an observation in the requested tenant-owned audit. */
@Repository
public class AuditActorRepository {
    private final JdbcClient jdbc;

    public AuditActorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Map<UUID, String> displayNames(UUID organizationId, UUID auditId) {
        return jdbc
                .sql("""
            SELECT u.id,u.display_name FROM app_user u JOIN (
                SELECT scanned_by_user_id AS actor FROM audit_scan
                  WHERE organization_id=:org AND container_audit_id=:audit
                UNION SELECT recorded_by_user_id FROM audit_finding
                  WHERE organization_id=:org AND container_audit_id=:audit
            ) observations ON observations.actor=u.id
            """)
                .param("org", organizationId)
                .param("audit", auditId)
                .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("display_name")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
