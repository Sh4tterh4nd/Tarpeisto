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
