package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Phase-10 HTTP authorization and tenant boundaries against real PostgreSQL. */
class Phase10ReviewControllerIntegrationTests extends AbstractIntegrationTest {
    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void reviewQueueIsOwnerDeputyOnly() {
        Fixture fixture = fixtureWithAllRoles();

        assertThat(exchange(fixture.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/findings", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(fixture.sessionFor(OrganizationRole.DEPUTY), HttpMethod.GET, "/api/v1/findings", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(
                                fixture.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                                HttpMethod.GET,
                                "/api/v1/findings",
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(fixture.sessionFor(OrganizationRole.VIEWER), HttpMethod.GET, "/api/v1/findings", null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void repairOpeningIsOwnerDeputyOnlyAndRepairListDoesNotCrossTenants() {
        Fixture ownerOrganization = fixtureWithAllRoles();
        Fixture otherOrganization = fixtureWithAllRoles();
        UUID assetId = insertSerializedAsset(ownerOrganization.organization());
        String path = "/api/v1/assets/" + assetId + "/repairs";

        assertThat(exchange(
                                ownerOrganization.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                                HttpMethod.POST,
                                path,
                                Map.of("referenceOrDescription", "replace cracked connector"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                                ownerOrganization.sessionFor(OrganizationRole.VIEWER),
                                HttpMethod.POST,
                                path,
                                Map.of("referenceOrDescription", "replace cracked connector"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(
                                ownerOrganization.sessionFor(OrganizationRole.DEPUTY),
                                HttpMethod.POST,
                                path,
                                Map.of("referenceOrDescription", "replace cracked connector"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(exchange(otherOrganization.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, path, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private UUID insertSerializedAsset(Organization organization) {
        UUID categoryId = UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        var now = java.sql.Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO category (id, organization_id, name, color, created_at, updated_at) VALUES (?, ?, ?, '#223344', ?, ?)",
                categoryId,
                organization.getId(),
                "Phase 10 category " + categoryId,
                now,
                now);
        jdbc.update(
                "INSERT INTO asset_model (id, organization_id, name, category_id, tracking_mode, can_contain_assets, created_at, updated_at) VALUES (?, ?, ?, ?, 'SERIALIZED_ASSET', false, ?, ?)",
                modelId,
                organization.getId(),
                "Phase 10 model " + modelId,
                categoryId,
                now,
                now);
        jdbc.update(
                "INSERT INTO physical_asset (id, organization_id, asset_model_id, public_code, unit_number, condition, lifecycle_state, created_at, updated_at) VALUES (?, ?, ?, ?, 1, 'GOOD', 'ACTIVE', ?, ?)",
                assetId,
                organization.getId(),
                modelId,
                "PT10A" + UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                now,
                now);
        return assetId;
    }

    @Test
    void sealHistoryIsReadableWithoutTakingAWriteLockAndSealMutationsAreRoleAndTenantScoped() {
        Fixture own = fixtureWithAllRoles(), foreign = fixtureWithAllRoles();
        UUID assetId = insertSerializedAsset(own.organization());
        jdbc.update(
                "UPDATE asset_model SET can_contain_assets=true WHERE id IN (SELECT asset_model_id FROM physical_asset WHERE id=?)",
                assetId);
        String settings = "/api/v1/assets/" + assetId + "/sealable";
        assertThat(exchange(
                                own.sessionFor(OrganizationRole.OPERATOR_AUDITOR),
                                HttpMethod.PUT,
                                settings,
                                Map.of("sealable", true))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchange(own.sessionFor(OrganizationRole.DEPUTY), HttpMethod.PUT, settings, Map.of("sealable", true))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        String history = "/api/v1/assets/" + assetId + "/seal/history";
        assertThat(exchange(own.sessionFor(OrganizationRole.VIEWER), HttpMethod.GET, history, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(foreign.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, history, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                foreign.sessionFor(OrganizationRole.OWNER),
                                HttpMethod.POST,
                                "/api/v1/assets/" + assetId + "/seal/break",
                                null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(
                                own.sessionFor(OrganizationRole.OWNER),
                                HttpMethod.POST,
                                "/api/v1/assets/" + assetId + "/seal/break",
                                Map.of("note", "History test"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchange(own.sessionFor(OrganizationRole.VIEWER), HttpMethod.GET, history, null)
                        .getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private org.springframework.http.ResponseEntity<String> exchange(
            AuthenticatedSession session, HttpMethod method, String path, Object body) {
        return restTemplate.exchange(path, method, new HttpEntity<>(body, session.headers()), String.class);
    }

    private Fixture fixtureWithAllRoles() {
        Organization organization = organizations.ensureOrganizationExists("Phase 10 Test Org " + UUID.randomUUID());
        Map<OrganizationRole, AuthenticatedSession> sessions = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = new User(
                    UUID.randomUUID(),
                    role.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID(),
                    null,
                    "Phase 10 " + role,
                    passwordEncoder.encode(PASSWORD),
                    true,
                    clock.instant());
            users.save(user);
            memberships.save(new OrganizationMembership(
                    UUID.randomUUID(), organization.getId(), user.getId(), role, clock.instant()));
            sessions.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(organization, sessions);
    }

    private record Fixture(Organization organization, Map<OrganizationRole, AuthenticatedSession> sessions) {
        AuthenticatedSession sessionFor(OrganizationRole role) {
            return sessions.get(role);
        }
    }
}
