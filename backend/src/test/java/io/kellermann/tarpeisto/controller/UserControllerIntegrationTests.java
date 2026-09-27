package io.kellermann.tarpeisto.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.ActivityLog;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ActivityLogRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers the plan section 3.3 security tests for the Owner-only user-administration endpoints:
 * role-based denial for every insufficient role, cross-organization isolation, the
 * client-supplied-organization-id-is-ignored contract, last-Owner protection, and activity-log
 * attribution.
 */
class UserControllerIntegrationTests extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void listingUsersIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();

        var response = exchange(fixture.sessionFor(role), HttpMethod.GET, "/api/v1/users", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void creatingAUserIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                "/api/v1/users",
                Map.of(
                        "username", "attempted-" + UUID.randomUUID(),
                        "password", "SomeLongEnoughPassword1",
                        "displayName", "Attempted User",
                        "role", "VIEWER"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void changingARoleIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.PUT,
                "/api/v1/users/" + fixture.userFor(OrganizationRole.VIEWER).getId() + "/role",
                Map.of("role", "DEPUTY"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void disablingAUserIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixtureWithAllRoles();

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.PUT,
                "/api/v1/users/" + fixture.userFor(OrganizationRole.VIEWER).getId() + "/enabled",
                Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ownerCanListCreateChangeRoleAndDisableUsersAndActivityIsRecorded() {
        Fixture fixture = fixtureWithAllRoles();
        AuthenticatedSession ownerSession = fixture.sessionFor(OrganizationRole.OWNER);

        var listResponse = exchange(ownerSession, HttpMethod.GET, "/api/v1/users", null);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody())
                .contains(fixture.userFor(OrganizationRole.VIEWER).getUsername());

        var createResponse = exchange(
                ownerSession,
                HttpMethod.POST,
                "/api/v1/users",
                Map.of(
                        "username", "created-by-owner-" + UUID.randomUUID(),
                        "password", "SomeLongEnoughPassword1",
                        "displayName", "Created User",
                        "role", "OPERATOR_AUDITOR"));
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String createdId = extractField(createResponse.getBody(), "id");
        assertThat(createdId).isNotBlank();
        UUID createdUserId = UUID.fromString(createdId);

        var changeRoleResponse = exchange(
                ownerSession, HttpMethod.PUT, "/api/v1/users/" + createdUserId + "/role", Map.of("role", "DEPUTY"));
        assertThat(changeRoleResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(membershipRepository
                        .findByOrganizationIdAndUserId(fixture.organization().getId(), createdUserId)
                        .orElseThrow()
                        .getRole())
                .isEqualTo(OrganizationRole.DEPUTY);

        var disableResponse = exchange(
                ownerSession, HttpMethod.PUT, "/api/v1/users/" + createdUserId + "/enabled", Map.of("enabled", false));
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(createdUserId).orElseThrow().isEnabled())
                .isFalse();

        List<ActivityLog> entries = activityLogRepository.findAllByOrganizationIdOrderByOccurredAtDesc(
                fixture.organization().getId());
        assertThat(entries)
                .filteredOn(entry ->
                        entry.getTargetId() != null && entry.getTargetId().equals(createdUserId))
                .extracting(ActivityLog::getAction)
                .contains("USER_CREATED", "USER_ROLE_CHANGED", "USER_DISABLED");
        assertThat(entries)
                .filteredOn(entry -> "USER_CREATED".equals(entry.getAction()))
                .extracting(ActivityLog::getActorUserId)
                .containsOnly(fixture.userFor(OrganizationRole.OWNER).getId());
    }

    @Test
    void aUserFromAnotherOrganizationIsNotVisibleOrMutable() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();
        User viewerInOrganizationA = organizationA.userFor(OrganizationRole.VIEWER);

        var listResponse =
                exchange(organizationB.sessionFor(OrganizationRole.OWNER), HttpMethod.GET, "/api/v1/users", null);
        assertThat(listResponse.getBody()).doesNotContain(viewerInOrganizationA.getUsername());

        var mutateResponse = exchange(
                organizationB.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/users/" + viewerInOrganizationA.getId() + "/role",
                Map.of("role", "DEPUTY"));
        // Not found, not forbidden: a record belonging to another organization must be
        // indistinguishable from a missing one (NotFoundException contract).
        assertThat(mutateResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(membershipRepository
                        .findByOrganizationIdAndUserId(
                                organizationA.organization().getId(), viewerInOrganizationA.getId())
                        .orElseThrow()
                        .getRole())
                .isEqualTo(OrganizationRole.VIEWER);
    }

    @Test
    void aClientSuppliedOrganizationIdQueryParameterCannotOverrideTheSessionOrganization() {
        Fixture organizationA = fixtureWithAllRoles();
        Fixture organizationB = fixtureWithAllRoles();

        var response = exchange(
                organizationA.sessionFor(OrganizationRole.OWNER),
                HttpMethod.GET,
                "/api/v1/users?organizationId=" + organizationB.organization().getId(),
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains(organizationA.userFor(OrganizationRole.VIEWER).getUsername())
                .doesNotContain(organizationB.userFor(OrganizationRole.VIEWER).getUsername());
    }

    @Test
    void theLastEnabledOwnerCannotBeDemoted() {
        Fixture fixture = fixtureWithAllRoles();
        User owner = fixture.userFor(OrganizationRole.OWNER);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/users/" + owner.getId() + "/role",
                Map.of("role", "DEPUTY"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("\"errorCode\":\"VALIDATION_FAILED\"");
        assertThat(membershipRepository
                        .findByOrganizationIdAndUserId(fixture.organization().getId(), owner.getId())
                        .orElseThrow()
                        .getRole())
                .isEqualTo(OrganizationRole.OWNER);
    }

    @Test
    void theLastEnabledOwnerCannotBeDisabled() {
        Fixture fixture = fixtureWithAllRoles();
        User owner = fixture.userFor(OrganizationRole.OWNER);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/users/" + owner.getId() + "/enabled",
                Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findById(owner.getId()).orElseThrow().isEnabled())
                .isTrue();
    }

    @Test
    void disablingAUserImmediatelyInvalidatesTheirExistingSession() {
        Fixture fixture = fixtureWithAllRoles();
        User viewer = fixture.userFor(OrganizationRole.VIEWER);
        AuthenticatedSession viewerSession = fixture.sessionFor(OrganizationRole.VIEWER);
        // The viewer's existing session works before being disabled.
        var beforeDisable = exchange(viewerSession, HttpMethod.GET, "/api/v1/session", null);
        assertThat(beforeDisable.getStatusCode()).isEqualTo(HttpStatus.OK);

        var disableResponse = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/users/" + viewer.getId() + "/enabled",
                Map.of("enabled", false));
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var afterDisable = exchange(viewerSession, HttpMethod.GET, "/api/v1/session", null);
        assertThat(afterDisable.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void demotingOneOfTwoOwnersSucceeds() {
        Fixture fixture = fixtureWithAllRoles();
        Organization organization = fixture.organization();
        User secondOwner = createUser(organization, "second-owner", OrganizationRole.OWNER);

        var response = exchange(
                fixture.sessionFor(OrganizationRole.OWNER),
                HttpMethod.PUT,
                "/api/v1/users/" + secondOwner.getId() + "/role",
                Map.of("role", "DEPUTY"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(membershipRepository
                        .findByOrganizationIdAndUserId(organization.getId(), secondOwner.getId())
                        .orElseThrow()
                        .getRole())
                .isEqualTo(OrganizationRole.DEPUTY);
    }

    private org.springframework.http.ResponseEntity<String> exchange(
            AuthenticatedSession session, HttpMethod method, String path, Object body) {
        var headers = session.headers();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    private static String extractField(String json, String fieldName) {
        var matcher = java.util.regex.Pattern.compile("\"" + fieldName + "\":\"([^\"]+)\"")
                .matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Fixture fixtureWithAllRoles() {
        Organization organization =
                organizationService.ensureOrganizationExists("User Controller Test Org " + UUID.randomUUID());
        Map<OrganizationRole, User> usersByRole = new EnumMap<>(OrganizationRole.class);
        Map<OrganizationRole, AuthenticatedSession> sessionsByRole = new EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = createUser(organization, role.name().toLowerCase(java.util.Locale.ROOT), role);
            usersByRole.put(role, user);
            sessionsByRole.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(organization, usersByRole, sessionsByRole);
    }

    private User createUser(Organization organization, String usernamePrefix, OrganizationRole role) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                usernamePrefix + "-" + UUID.randomUUID(),
                null,
                "Test User " + usernamePrefix,
                passwordEncoder.encode(PASSWORD),
                true,
                now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }

    private record Fixture(
            Organization organization,
            Map<OrganizationRole, User> usersByRole,
            Map<OrganizationRole, AuthenticatedSession> sessionsByRole) {

        User userFor(OrganizationRole role) {
            return usersByRole.get(role);
        }

        AuthenticatedSession sessionFor(OrganizationRole role) {
            return sessionsByRole.get(role);
        }
    }
}
