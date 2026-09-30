package io.kellermann.tarpeisto.repository;

import io.kellermann.tarpeisto.model.TemporaryAccessInvitation;
import io.kellermann.tarpeisto.model.VolunteerSession;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Explicit grant and scope projections; no inventory API is used to authorize a volunteer. */
@Repository
public class TemporaryAccessRepository {
    private final JdbcClient jdbc;

    public TemporaryAccessRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String TASK_SCOPE = """
        WITH scoped_tasks AS (
          SELECT t.* FROM audit_task t JOIN audit_batch b ON b.id=t.audit_batch_id AND b.organization_id=t.organization_id
          JOIN temporary_access_invitation i ON i.organization_id=t.organization_id
          WHERE i.id=:invitation AND i.organization_id=:org
            AND (i.audit_batch_id=b.id OR (i.booking_id=b.event_booking_id))
        )
        """;
    private static final String ASSET_SCOPE = TASK_SCOPE + """
        , scoped_assets AS (
          SELECT container_asset_id AS id FROM scoped_tasks
          UNION SELECT a.id FROM physical_asset a JOIN scoped_tasks t ON a.parent_container_asset_id=t.container_asset_id WHERE a.organization_id=:org
          UNION SELECT r.specific_asset_id FROM packing_requirement r JOIN scoped_tasks t ON r.container_asset_id=t.container_asset_id WHERE r.organization_id=:org AND r.archived_at IS NULL AND r.specific_asset_id IS NOT NULL
          UNION SELECT r.specific_asset_id FROM audit_expected_requirement r JOIN container_audit a ON a.id=r.container_audit_id
            JOIN scoped_tasks t ON t.id=a.audit_task_id WHERE r.organization_id=:org AND r.specific_asset_id IS NOT NULL
          UNION SELECT s.physical_asset_id FROM audit_scan s JOIN container_audit a ON a.id=s.container_audit_id
            JOIN scoped_tasks t ON t.id=a.audit_task_id WHERE s.organization_id=:org
          UNION SELECT ma.physical_asset_id FROM checkout_manifest_asset ma
            JOIN audit_batch b ON b.checkout_manifest_id=ma.checkout_manifest_id AND b.organization_id=ma.organization_id
            JOIN temporary_access_invitation i ON i.organization_id=b.organization_id
            WHERE i.id=:invitation AND i.organization_id=:org
              AND (i.audit_batch_id=b.id OR (i.booking_id=b.event_booking_id))
              AND EXISTS(SELECT 1 FROM scoped_tasks t WHERE t.container_asset_id=ma.physical_asset_id
                OR t.container_asset_id=ma.container_asset_id OR t.container_asset_id=ma.physical_parent_container_asset_id)
        )
        """;

    public void insert(TemporaryAccessInvitation invitation, String digest, UUID actor) {
        jdbc.sql(
                        "INSERT INTO temporary_access_invitation(id,organization_id,booking_id,audit_batch_id,token_hash,issued_by_user_id,issued_at,expires_at) VALUES (:id,:org,:booking,:batch,:hash,:actor,:issued,:expires)")
                .param("id", invitation.id())
                .param("org", invitation.organizationId())
                .param("booking", invitation.bookingId())
                .param("batch", invitation.auditBatchId())
                .param("hash", digest)
                .param("actor", actor)
                .param("issued", invitation.issuedAt().atOffset(java.time.ZoneOffset.UTC))
                .param("expires", invitation.expiresAt().atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    public Optional<TemporaryAccessInvitation> findByDigest(String digest) {
        return jdbc.sql("SELECT * FROM temporary_access_invitation WHERE token_hash=:hash")
                .param("hash", digest)
                .query(this::invitation)
                .optional();
    }

    public Optional<TemporaryAccessInvitation> find(UUID org, UUID id) {
        return jdbc.sql("SELECT * FROM temporary_access_invitation WHERE organization_id=:org AND id=:id")
                .param("org", org)
                .param("id", id)
                .query(this::invitation)
                .optional();
    }

    public List<TemporaryAccessInvitation> list(UUID org, UUID booking, UUID batch) {
        return jdbc.sql(
                        "SELECT * FROM temporary_access_invitation WHERE organization_id=:org AND (booking_id=:booking OR audit_batch_id=:batch) ORDER BY issued_at DESC LIMIT 100")
                .param("org", org)
                .param("booking", booking)
                .param("batch", batch)
                .query(this::invitation)
                .list();
    }

    public void revoke(UUID org, UUID id, UUID actor, Instant now) {
        jdbc.sql(
                        "UPDATE temporary_access_invitation SET revoked_at=:now,revoked_by_user_id=:actor WHERE organization_id=:org AND id=:id AND revoked_at IS NULL")
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("actor", actor)
                .param("org", org)
                .param("id", id)
                .update();
    }

    public void createVolunteer(
            UUID org,
            UUID invitation,
            UUID session,
            UUID user,
            UUID operation,
            String name,
            Instant now,
            Instant expires) {
        jdbc.sql(
                        "INSERT INTO app_user(id,username,display_name,enabled,temporary_identity,created_at,updated_at) VALUES (:id,:username,:name,true,true,:now,:now)")
                .param("id", user)
                .param("username", "volunteer-" + user)
                .param("name", name)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .update();
        jdbc.sql(
                        "INSERT INTO volunteer_session(id,organization_id,invitation_id,user_id,redemption_operation_id,display_name,created_at,expires_at) VALUES (:id,:org,:invitation,:user,:op,:name,:now,:expires)")
                .param("id", session)
                .param("org", org)
                .param("invitation", invitation)
                .param("user", user)
                .param("op", operation)
                .param("name", name)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .param("expires", expires.atOffset(java.time.ZoneOffset.UTC))
                .update();
    }

    public Optional<VolunteerSession> redemption(UUID invitation, UUID operation) {
        return jdbc.sql(
                        "SELECT s.*,u.username,i.booking_id,i.audit_batch_id FROM volunteer_session s JOIN app_user u ON u.id=s.user_id JOIN temporary_access_invitation i ON i.id=s.invitation_id WHERE s.invitation_id=:id AND s.redemption_operation_id=:op")
                .param("id", invitation)
                .param("op", operation)
                .query(this::session)
                .optional();
    }

    public Optional<VolunteerSession> validSession(UUID org, UUID session, UUID user, Instant now) {
        return jdbc.sql(
                        "SELECT s.*,u.username,i.booking_id,i.audit_batch_id FROM volunteer_session s JOIN app_user u ON u.id=s.user_id JOIN temporary_access_invitation i ON i.id=s.invitation_id AND i.organization_id=s.organization_id WHERE s.organization_id=:org AND s.id=:session AND s.user_id=:user AND u.enabled AND u.temporary_identity AND i.revoked_at IS NULL AND s.expires_at>:now AND i.expires_at>:now")
                .param("org", org)
                .param("session", session)
                .param("user", user)
                .param("now", now.atOffset(java.time.ZoneOffset.UTC))
                .query(this::session)
                .optional();
    }

    public boolean taskAllowed(UUID org, UUID invitation, UUID task) {
        return jdbc.sql(TASK_SCOPE + "SELECT EXISTS(SELECT 1 FROM scoped_tasks WHERE id=:id)")
                .param("org", org)
                .param("invitation", invitation)
                .param("id", task)
                .query(Boolean.class)
                .single();
    }

    public boolean auditAllowed(UUID org, UUID invitation, UUID audit) {
        return jdbc.sql(
                        TASK_SCOPE
                                + "SELECT EXISTS(SELECT 1 FROM container_audit a JOIN scoped_tasks t ON t.id=a.audit_task_id WHERE a.organization_id=:org AND a.id=:id)")
                .param("org", org)
                .param("invitation", invitation)
                .param("id", audit)
                .query(Boolean.class)
                .single();
    }

    public boolean assetAllowed(UUID org, UUID invitation, UUID asset) {
        return jdbc.sql(ASSET_SCOPE + "SELECT EXISTS(SELECT 1 FROM scoped_assets WHERE id=:id)")
                .param("org", org)
                .param("invitation", invitation)
                .param("id", asset)
                .query(Boolean.class)
                .single();
    }

    public boolean modelAllowed(UUID org, UUID invitation, UUID model) {
        return jdbc.sql(
                        ASSET_SCOPE
                                + "SELECT EXISTS(SELECT 1 FROM physical_asset a JOIN scoped_assets s ON s.id=a.id WHERE a.organization_id=:org AND a.asset_model_id=:id) OR EXISTS(SELECT 1 FROM packing_requirement r JOIN scoped_tasks t ON t.container_asset_id=r.container_asset_id WHERE r.organization_id=:org AND r.archived_at IS NULL AND r.asset_model_id=:id) OR EXISTS(SELECT 1 FROM audit_expected_requirement r JOIN container_audit a ON a.id=r.container_audit_id JOIN scoped_tasks t ON t.id=a.audit_task_id WHERE r.organization_id=:org AND r.asset_model_id=:id)")
                .param("org", org)
                .param("invitation", invitation)
                .param("id", model)
                .query(Boolean.class)
                .single();
    }

    public List<UUID> assignedTaskIds(UUID org, UUID invitation) {
        return jdbc.sql(TASK_SCOPE + "SELECT id FROM scoped_tasks ORDER BY created_at,id")
                .param("org", org)
                .param("invitation", invitation)
                .query(UUID.class)
                .list();
    }

    private TemporaryAccessInvitation invitation(ResultSet rs, int row) throws SQLException {
        return new TemporaryAccessInvitation(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("booking_id", UUID.class),
                rs.getObject("audit_batch_id", UUID.class),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("revoked_at") == null
                        ? null
                        : rs.getTimestamp("revoked_at").toInstant());
    }

    private VolunteerSession session(ResultSet rs, int row) throws SQLException {
        return new VolunteerSession(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("invitation_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getObject("booking_id", UUID.class),
                rs.getObject("audit_batch_id", UUID.class));
    }
}
