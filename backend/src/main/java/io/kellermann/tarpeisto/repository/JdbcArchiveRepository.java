package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.model.ArchiveKind;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcArchiveRepository {
    public record State(UUID id, boolean archived, long version, String username) {}

    private final JdbcClient jdbc;

    public JdbcArchiveRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public State lock(UUID org, ArchiveKind kind, UUID id) {
        if (kind == ArchiveKind.AUDIT) {
            if (!exists("SELECT EXISTS(SELECT 1 FROM container_audit WHERE organization_id=:org AND id=:id)", org, id))
                throw new NotFoundException("Audit not found.");
            jdbc.sql(
                            "INSERT INTO container_audit_archive(container_audit_id,organization_id) VALUES(:id,:org) ON CONFLICT DO NOTHING")
                    .param("id", id)
                    .param("org", org)
                    .update();
        }
        String table = table(kind);
        String key = kind == ArchiveKind.AUDIT ? "container_audit_id" : "id";
        String scope = kind == ArchiveKind.USER
                ? "EXISTS(SELECT 1 FROM organization_membership m WHERE m.user_id=t.id AND m.organization_id=:org) AND NOT t.temporary_identity"
                : "t.organization_id=:org";
        return jdbc.sql("SELECT t." + key + " AS id,t.archived_at IS NOT NULL AS archived,t.version,"
                        + (kind == ArchiveKind.USER ? "t.username" : "NULL::text AS username")
                        + " FROM " + table + " t WHERE t." + key + "=:id AND " + scope + " FOR UPDATE OF t")
                .param("id", id)
                .param("org", org)
                .query((rs, row) -> new State(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("archived"),
                        rs.getLong("version"),
                        rs.getString("username")))
                .optional()
                .orElseThrow(() -> new NotFoundException("Record not found."));
    }

    public void requireEligible(UUID org, ArchiveKind kind, UUID id) {
        String eligible = switch (kind) {
            case STOCK -> """
                SELECT EXISTS(SELECT 1 FROM consumable_stock_balance s WHERE s.organization_id=:org AND s.id=:id AND s.quantity=0
                  AND NOT EXISTS(SELECT 1 FROM event_booking_reservation_claim c JOIN event_booking b
                    ON b.organization_id=c.organization_id AND b.current_revision_id=c.reservation_revision_id
                    WHERE c.organization_id=:org AND c.consumable_stock_id=s.id AND b.status IN ('RESERVED','CHECKED_OUT','RETURNED_AUDITS_PENDING','REVIEW_REQUIRED'))
                    AND NOT EXISTS(SELECT 1 FROM checkout_manifest_consumable c WHERE c.organization_id=:org AND c.consumable_stock_balance_id=s.id AND c.semantics='SEPARATELY_ISSUED' AND c.accounted_at IS NULL)
                  AND NOT EXISTS(SELECT 1 FROM container_audit a WHERE a.organization_id=:org AND a.container_asset_id=s.container_asset_id AND a.state='IN_PROGRESS'))
                """;
            case BOOKING -> """
                SELECT EXISTS(SELECT 1 FROM event_booking b WHERE b.organization_id=:org AND b.id=:id AND b.status='COMPLETED'
                  AND NOT EXISTS(SELECT 1 FROM checkout_manifest m JOIN checkout_manifest_asset a ON a.checkout_manifest_id=m.id AND a.organization_id=m.organization_id
                    WHERE m.organization_id=:org AND m.event_booking_id=b.id AND a.audit_released_at IS NULL)
                  AND NOT EXISTS(SELECT 1 FROM checkout_manifest m JOIN checkout_manifest_consumable c ON c.checkout_manifest_id=m.id AND c.organization_id=m.organization_id
                    WHERE m.organization_id=:org AND m.event_booking_id=b.id AND c.semantics='SEPARATELY_ISSUED' AND c.accounted_at IS NULL)
                  AND NOT EXISTS(SELECT 1 FROM audit_batch ab JOIN audit_task t ON t.audit_batch_id=ab.id AND t.organization_id=ab.organization_id
                    WHERE ab.organization_id=:org AND ab.event_booking_id=b.id AND t.state<>'COMPLETED')
                  AND NOT EXISTS(SELECT 1 FROM audit_batch ab JOIN container_audit a ON a.audit_batch_id=ab.id AND a.organization_id=ab.organization_id
                    JOIN audit_finding f ON f.container_audit_id=a.id AND f.organization_id=a.organization_id
                    WHERE ab.organization_id=:org AND ab.event_booking_id=b.id AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=:org AND r.audit_finding_id=f.id)))
                """;
            case AUDIT -> """
                SELECT EXISTS(SELECT 1 FROM container_audit a WHERE a.organization_id=:org AND a.id=:id AND a.state='COMPLETED'
                  AND NOT EXISTS(SELECT 1 FROM audit_finding f WHERE f.organization_id=:org AND f.container_audit_id=a.id
                    AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=:org AND r.audit_finding_id=f.id))
                  AND NOT EXISTS(SELECT 1 FROM audit_task_dependency d JOIN audit_task t ON t.id=d.audit_task_id AND t.organization_id=d.organization_id
                    WHERE d.organization_id=:org AND d.depends_on_audit_task_id=a.audit_task_id AND t.state<>'COMPLETED')
                  AND NOT EXISTS(SELECT 1 FROM checkout_manifest_asset m WHERE m.organization_id=:org AND m.physical_asset_id=a.container_asset_id AND m.audit_released_at IS NULL)
                  AND NOT EXISTS(SELECT 1 FROM audit_batch b JOIN checkout_manifest_consumable c ON c.checkout_manifest_id=b.checkout_manifest_id AND c.organization_id=b.organization_id
                    WHERE b.organization_id=:org AND b.id=a.audit_batch_id AND c.container_asset_id=a.container_asset_id AND c.semantics='SEPARATELY_ISSUED' AND c.accounted_at IS NULL))
                """;
            case USER -> "SELECT TRUE WHERE CAST(:org AS uuid) IS NOT NULL AND CAST(:id AS uuid) IS NOT NULL";
        };
        if (!exists(eligible, org, id))
            throw new ArchiveConflictException("Record still has operational work or is not eligible for archive.");
        if (kind == ArchiveKind.BOOKING && !exists("""
            SELECT NOT EXISTS(SELECT 1 FROM audit_batch b JOIN audit_task t ON t.audit_batch_id=b.id AND t.organization_id=b.organization_id
              WHERE b.organization_id=:org AND b.event_booking_id=:id AND NOT EXISTS(SELECT 1 FROM container_audit a
                WHERE a.organization_id=:org AND a.audit_task_id=t.id AND a.current_attempt AND a.state='COMPLETED'))
            """, org, id))
            throw new ArchiveConflictException("Every task needs a completed current audit.");
        if (kind == ArchiveKind.AUDIT && !exists("""
            SELECT NOT EXISTS(SELECT 1 FROM container_audit selected JOIN audit_task t ON t.audit_batch_id=selected.audit_batch_id AND t.organization_id=selected.organization_id
              WHERE selected.organization_id=:org AND selected.id=:id AND (t.state<>'COMPLETED' OR NOT EXISTS(SELECT 1 FROM container_audit a
                WHERE a.organization_id=:org AND a.audit_task_id=t.id AND a.current_attempt AND a.state='COMPLETED')))
              AND NOT EXISTS(SELECT 1 FROM container_audit selected JOIN container_audit a ON a.audit_batch_id=selected.audit_batch_id AND a.organization_id=selected.organization_id
                JOIN audit_finding f ON f.container_audit_id=a.id AND f.organization_id=a.organization_id WHERE selected.organization_id=:org AND selected.id=:id
                  AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=:org AND r.audit_finding_id=f.id))
              AND NOT EXISTS(SELECT 1 FROM container_audit a JOIN audit_batch b ON b.id=a.audit_batch_id AND b.organization_id=a.organization_id
                JOIN event_booking e ON e.id=b.event_booking_id AND e.organization_id=b.organization_id WHERE a.organization_id=:org AND a.id=:id AND e.status<>'COMPLETED')
            """, org, id))
            throw new ArchiveConflictException("Audit batch still has operational work.");
        if (kind == ArchiveKind.STOCK && !exists("""
            WITH RECURSIVE ancestors AS (
              SELECT a.id,a.parent_container_asset_id FROM physical_asset a JOIN consumable_stock_balance s ON s.container_asset_id=a.id AND s.organization_id=a.organization_id WHERE s.organization_id=:org AND s.id=:id
              UNION ALL SELECT a.id,a.parent_container_asset_id FROM physical_asset a JOIN ancestors p ON a.id=p.parent_container_asset_id WHERE a.organization_id=:org)
            SELECT NOT EXISTS(SELECT 1 FROM event_booking_line l JOIN event_booking b ON b.id=l.event_booking_id AND b.organization_id=l.organization_id
              WHERE l.organization_id=:org AND l.consumable_stock_id=:id AND l.archived_at IS NULL AND b.archived_at IS NULL AND b.status IN ('DRAFT','RESERVED','CHECKED_OUT','RETURNED_AUDITS_PENDING','REVIEW_REQUIRED'))
              AND NOT EXISTS(SELECT 1 FROM audit_task t WHERE t.organization_id=:org AND t.container_asset_id IN(SELECT id FROM ancestors) AND t.state<>'COMPLETED')
              AND NOT EXISTS(SELECT 1 FROM checkout_manifest_asset m WHERE m.organization_id=:org AND m.physical_asset_id IN(SELECT id FROM ancestors) AND m.audit_released_at IS NULL)
              AND NOT EXISTS(SELECT 1 FROM container_audit a JOIN audit_finding f ON f.container_audit_id=a.id AND f.organization_id=a.organization_id
                WHERE a.organization_id=:org AND a.container_asset_id IN(SELECT id FROM ancestors) AND NOT EXISTS(SELECT 1 FROM audit_finding_resolution r WHERE r.organization_id=:org AND r.audit_finding_id=f.id))
            """, org, id))
            throw new ArchiveConflictException("Stock place still has operational work.");
        if (kind == ArchiveKind.USER) requireOwnerRemains(org, id);
        if (kind == ArchiveKind.AUDIT)
            jdbc.sql(
                            "SELECT b.event_booking_id FROM container_audit a JOIN audit_batch b ON b.id=a.audit_batch_id AND b.organization_id=a.organization_id WHERE a.organization_id=:org AND a.id=:id AND b.event_booking_id IS NOT NULL")
                    .param("org", org)
                    .param("id", id)
                    .query(UUID.class)
                    .optional()
                    .ifPresent(booking -> requireEligible(org, ArchiveKind.BOOKING, booking));
    }

    public void requireSingleOrganizationUser(UUID org, UUID id) {
        if (!exists(
                "SELECT NOT EXISTS(SELECT 1 FROM organization_membership WHERE user_id=:id AND organization_id<>:org)",
                org,
                id))
            throw new ArchiveConflictException("An account belonging to another organization cannot be archived here.");
    }

    public void requireOwnerRemains(UUID org, UUID id) {
        boolean removesOwner = exists(
                "SELECT EXISTS(SELECT 1 FROM organization_membership m JOIN app_user u ON u.id=m.user_id WHERE m.organization_id=:org AND m.user_id=:id AND m.role='OWNER' AND u.enabled)",
                org,
                id);
        if (!removesOwner) return;
        if (!exists(
                "SELECT EXISTS(SELECT 1 FROM organization_membership m JOIN app_user u ON u.id=m.user_id WHERE m.organization_id=:org AND m.user_id<>:id AND m.role='OWNER' AND u.enabled AND u.archived_at IS NULL)",
                org,
                id)) throw new ArchiveConflictException("At least one enabled Owner must remain.");
        boolean localOwner = exists(
                "SELECT EXISTS(SELECT 1 FROM app_user WHERE id=:id AND password_hash IS NOT NULL AND CAST(:org AS uuid) IS NOT NULL)",
                org,
                id);
        if (localOwner
                && !exists(
                        "SELECT EXISTS(SELECT 1 FROM organization_membership m JOIN app_user u ON u.id=m.user_id WHERE m.organization_id=:org AND m.user_id<>:id AND m.role='OWNER' AND u.enabled AND u.archived_at IS NULL AND u.password_hash IS NOT NULL)",
                        org,
                        id)) throw new ArchiveConflictException("At least one enabled local Owner must remain.");
    }

    public void change(UUID org, ArchiveKind kind, UUID id, boolean archived, Instant now) {
        String key = kind == ArchiveKind.AUDIT ? "container_audit_id" : "id";
        jdbc.sql("UPDATE " + table(kind) + " SET archived_at=:at,version=version+1"
                        + (kind == ArchiveKind.USER ? ",enabled=false,updated_at=:now" : "")
                        + " WHERE " + key + "=:id AND "
                        + (kind == ArchiveKind.USER
                                ? "EXISTS(SELECT 1 FROM organization_membership m WHERE m.user_id=app_user.id AND m.organization_id=:org)"
                                : "organization_id=:org"))
                .param("id", id)
                .param("org", org)
                .param("at", archived ? now.atOffset(ZoneOffset.UTC) : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    private boolean exists(String sql, UUID org, UUID id) {
        return jdbc.sql(sql)
                .param("org", org)
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    private static String table(ArchiveKind kind) {
        return switch (kind) {
            case STOCK -> "consumable_stock_balance";
            case USER -> "app_user";
            case BOOKING -> "event_booking";
            case AUDIT -> "container_audit_archive";
        };
    }

    public State auditState(UUID org, UUID id) {
        return jdbc.sql(
                        "SELECT container_audit_id AS id,archived_at IS NOT NULL AS archived,version FROM container_audit_archive WHERE organization_id=:org AND container_audit_id=:id")
                .param("org", org)
                .param("id", id)
                .query((rs, row) -> new State(
                        rs.getObject("id", UUID.class), rs.getBoolean("archived"), rs.getLong("version"), null))
                .optional()
                .orElse(new State(id, false, 0, null));
    }

    public boolean taskArchived(UUID org, UUID task) {
        return exists("""
            SELECT EXISTS(SELECT 1 FROM audit_task t JOIN audit_batch b ON b.id=t.audit_batch_id AND b.organization_id=t.organization_id
              LEFT JOIN event_booking e ON e.id=b.event_booking_id AND e.organization_id=b.organization_id
              WHERE t.organization_id=:org AND t.id=:id AND (e.archived_at IS NOT NULL OR EXISTS(SELECT 1 FROM container_audit a
                JOIN container_audit_archive h ON h.container_audit_id=a.id AND h.organization_id=a.organization_id
                WHERE a.organization_id=:org AND a.audit_task_id=t.id AND a.current_attempt AND h.archived_at IS NOT NULL)))
            """, org, task);
    }
}
