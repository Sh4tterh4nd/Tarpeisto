package io.kellermann.bigcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves that Flyway applies cleanly to an empty PostgreSQL database and that Hibernate then
 * validates successfully against the resulting schema. Both are prerequisites for the Spring
 * context in {@link AbstractIntegrationTest} to start at all
 * ({@code spring.jpa.hibernate.ddl-auto=validate}): reaching the assertions below is itself part
 * of the proof, but the row-level checks additionally confirm the exact tables Phase 0 expects.
 */
class MigrationIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void organizationTableExistsAndIsQueryable() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM organization", Integer.class);

        assertThat(count).isNotNull();
    }

    @Test
    void springSessionJdbcSchemaExistsAndIsQueryable() {
        Integer sessionCount = jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session", Integer.class);
        Integer attributeCount =
                jdbcTemplate.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class);

        assertThat(sessionCount).isNotNull();
        assertThat(attributeCount).isNotNull();
    }

    @Test
    void phase1IdentityTablesExistAndAreQueryable() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM organization_membership", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM external_identity", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM activity_log", Integer.class))
                .isNotNull();
    }

    @Test
    void phase7BookingReservationTablesExistAndAreQueryable() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event_booking", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event_booking_line", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM event_booking_reservation_revision", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM event_booking_reservation_claim", Integer.class))
                .isNotNull();
    }

    @Test
    @Transactional
    void externalIdentityEnforcesUniqueIssuerAndSubject() {
        UUID organizationId = insertOrganization();
        UUID userId = insertUser(organizationId);
        String issuer = "https://issuer.example/" + UUID.randomUUID();
        String subject = "subject-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO external_identity (id, user_id, issuer, subject, created_at) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                userId,
                issuer,
                subject,
                java.sql.Timestamp.from(Instant.now()));

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "INSERT INTO external_identity (id, user_id, issuer, subject, created_at) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(),
                        userId,
                        issuer,
                        subject,
                        java.sql.Timestamp.from(Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void formalLossAccountingReleasesCustodyWithoutInventingPhysicalReturn() {
        ManifestAssetFixture fixture = insertManifestAssetFixture();
        jdbcTemplate.update(
                "INSERT INTO audit_finding_resolution (id, organization_id, audit_finding_id, action, operation_id, fingerprint, actor_user_id, resolved_at) VALUES (?, ?, ?, 'MARK_LOST', ?, ?, ?, ?)",
                fixture.resolutionId(),
                fixture.organizationId(),
                fixture.findingId(),
                UUID.randomUUID(),
                "test-fingerprint",
                fixture.userId(),
                java.sql.Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "INSERT INTO manifest_asset_accounting (id, organization_id, checkout_manifest_asset_id, audit_finding_resolution_id, accounting_state, accounted_by_user_id, accounted_at) VALUES (?, ?, ?, ?, 'LOST', ?, ?)",
                UUID.randomUUID(),
                fixture.organizationId(),
                fixture.manifestAssetId(),
                fixture.resolutionId(),
                fixture.userId(),
                java.sql.Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "UPDATE checkout_manifest_asset SET audit_released_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now()),
                fixture.manifestAssetId());

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT returned_at IS NULL AND audit_released_at IS NOT NULL FROM checkout_manifest_asset WHERE id = ?",
                        Boolean.class,
                        fixture.manifestAssetId()))
                .isTrue();
    }

    @Test
    @Transactional
    void nonPhysicalManifestReleaseIsRejectedWithoutFormalAccounting() {
        ManifestAssetFixture fixture = insertManifestAssetFixture();

        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE checkout_manifest_asset SET audit_released_at = ? WHERE id = ?",
                        java.sql.Timestamp.from(Instant.now()),
                        fixture.manifestAssetId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ManifestAssetFixture insertManifestAssetFixture() {
        Instant now = Instant.now();
        UUID organizationId = insertOrganization();
        UUID userId = insertUser(organizationId);
        UUID categoryId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID manifestId = UUID.randomUUID();
        UUID manifestAssetId = UUID.randomUUID();
        UUID findingId = UUID.randomUUID();
        UUID resolutionId = UUID.randomUUID();
        java.sql.Timestamp timestamp = java.sql.Timestamp.from(now);
        jdbcTemplate.update(
                "INSERT INTO category (id, organization_id, name, color, created_at, updated_at) VALUES (?, ?, ?, '#223344', ?, ?)",
                categoryId,
                organizationId,
                "Migration category " + categoryId,
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO asset_model (id, organization_id, name, category_id, tracking_mode, can_contain_assets, created_at, updated_at) VALUES (?, ?, ?, ?, 'SERIALIZED_ASSET', false, ?, ?)",
                modelId,
                organizationId,
                "Migration model " + modelId,
                categoryId,
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO physical_asset (id, organization_id, asset_model_id, public_code, unit_number, condition, lifecycle_state, created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'GOOD', 'ACTIVE', ?, ?)",
                assetId,
                organizationId,
                modelId,
                "ABCDE0",
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO event_booking (id, organization_id, name, creation_mutation_id, creation_fingerprint, created_by_user_id, reservation_status, starts_at, ends_at, status, created_at, updated_at) VALUES (?, ?, 'Migration booking', ?, 'fingerprint', ?, 'NONE', ?, ?, 'CHECKED_OUT', ?, ?)",
                bookingId,
                organizationId,
                UUID.randomUUID(),
                userId,
                timestamp,
                java.sql.Timestamp.from(now.plusSeconds(3600)),
                timestamp,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO event_booking_reservation_revision (id, organization_id, event_booking_id, revision_number, action, actor_user_id, occurred_at) VALUES (?, ?, ?, 1, 'RESERVED', ?, ?)",
                revisionId,
                organizationId,
                bookingId,
                userId,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO checkout_manifest (id, organization_id, event_booking_id, reservation_revision_id, checkout_mutation_id, checkout_fingerprint, booking_snapshot, checked_out_by_user_id, checked_out_at) VALUES (?, ?, ?, ?, ?, 'fingerprint', '{}'::jsonb, ?, ?)",
                manifestId,
                organizationId,
                bookingId,
                revisionId,
                UUID.randomUUID(),
                userId,
                timestamp);
        jdbcTemplate.update(
                "INSERT INTO checkout_manifest_asset (id, organization_id, checkout_manifest_id, physical_asset_id, is_container, asset_snapshot, container_snapshot) VALUES (?, ?, ?, ?, false, '{}'::jsonb, '{}'::jsonb)",
                manifestAssetId,
                organizationId,
                manifestId,
                assetId);
        jdbcTemplate.update(
                "INSERT INTO audit_finding (id, organization_id, physical_asset_id, finding_type, detail, recorded_by_user_id, recorded_at) VALUES (?, ?, ?, 'MISSING', '{}'::jsonb, ?, ?)",
                findingId,
                organizationId,
                assetId,
                userId,
                timestamp);
        return new ManifestAssetFixture(organizationId, userId, manifestAssetId, findingId, resolutionId);
    }

    @Test
    @Transactional
    void lastVerifiedAuditReferenceRejectsAnotherOrganization() {
        ManifestAssetFixture own = insertManifestAssetFixture();
        ManifestAssetFixture foreign = insertManifestAssetFixture();
        UUID foreignAsset = jdbcTemplate.queryForObject(
                "SELECT physical_asset_id FROM checkout_manifest_asset WHERE id=?",
                UUID.class,
                foreign.manifestAssetId());
        UUID foreignManifest = jdbcTemplate.queryForObject(
                "SELECT checkout_manifest_id FROM checkout_manifest_asset WHERE id=?",
                UUID.class,
                foreign.manifestAssetId());
        UUID foreignBooking = jdbcTemplate.queryForObject(
                "SELECT event_booking_id FROM checkout_manifest WHERE id=?", UUID.class, foreignManifest);
        UUID batch = UUID.randomUUID(), task = UUID.randomUUID(), audit = UUID.randomUUID();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbcTemplate.update(
                "INSERT INTO audit_batch(id,organization_id,event_booking_id,checkout_manifest_id,created_by_user_id,created_at) VALUES (?,?,?,?,?,?)",
                batch,
                foreign.organizationId(),
                foreignBooking,
                foreignManifest,
                foreign.userId(),
                now);
        jdbcTemplate.update(
                "INSERT INTO audit_task(id,organization_id,audit_batch_id,container_asset_id,state,created_at) VALUES (?,?,?,?,'READY',?)",
                task,
                foreign.organizationId(),
                batch,
                foreignAsset,
                now);
        jdbcTemplate.update(
                "INSERT INTO container_audit(id,organization_id,audit_batch_id,audit_task_id,container_asset_id,state,started_by_user_id,started_at) VALUES (?,?,?,?,?,'IN_PROGRESS',?,?)",
                audit,
                foreign.organizationId(),
                batch,
                task,
                foreignAsset,
                foreign.userId(),
                now);
        assertThatThrownBy(() -> jdbcTemplate.update(
                        "UPDATE physical_asset SET last_verified_at=?,last_verified_audit_id=? WHERE id IN (SELECT physical_asset_id FROM checkout_manifest_asset WHERE id=?)",
                        now,
                        audit,
                        own.manifestAssetId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private record ManifestAssetFixture(
            UUID organizationId, UUID userId, UUID manifestAssetId, UUID findingId, UUID resolutionId) {}

    private UUID insertOrganization() {
        UUID organizationId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO organization (id, name, created_at, updated_at) VALUES (?, ?, ?, ?)",
                organizationId,
                "Migration Test Org " + organizationId,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return organizationId;
    }

    private UUID insertUser(UUID organizationId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO app_user (id, username, display_name, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, ?, ?)",
                userId,
                "migration-test-" + userId,
                "Migration Test User",
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        jdbcTemplate.update(
                "INSERT INTO organization_membership (id, organization_id, user_id, role, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'VIEWER', ?, ?)",
                UUID.randomUUID(),
                organizationId,
                userId,
                java.sql.Timestamp.from(Instant.now()),
                java.sql.Timestamp.from(Instant.now()));
        return userId;
    }
}
