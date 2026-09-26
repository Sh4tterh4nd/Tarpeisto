package io.kellermann.bigcontainers.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.PermissionTestSupport;
import io.kellermann.bigcontainers.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.bigcontainers.service.OrganizationService;
import java.time.Clock;
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
 * HTTP-level coverage of the Owner-only external-identity administration endpoints (plan section
 * 3.3): role-based denial for every insufficient role and Owner happy-path list/link/unlink,
 * mirroring {@code UserControllerIntegrationTests}.
 */
class ExternalIdentityControllerIntegrationTests extends AbstractIntegrationTest {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void listingIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixture();

        var response = exchange(
                fixture.sessionFor(role), HttpMethod.GET, path(fixture.viewer().getId()), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void creatingALinkIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixture();

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.POST,
                path(fixture.viewer().getId()),
                Map.of("issuer", "https://idp.example.org", "subject", "sub-" + UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @ParameterizedTest
    @EnumSource(value = OrganizationRole.class, names = "OWNER", mode = EnumSource.Mode.EXCLUDE)
    void unlinkingIsDeniedForInsufficientRoles(OrganizationRole role) {
        Fixture fixture = fixture();

        var response = exchange(
                fixture.sessionFor(role),
                HttpMethod.DELETE,
                path(fixture.viewer().getId()) + "/" + UUID.randomUUID(),
                null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void ownerCanListCreateAndUnlinkAnExternalIdentity() {
        Fixture fixture = fixture();
        AuthenticatedSession ownerSession = fixture.sessionFor(OrganizationRole.OWNER);

        var emptyList =
                exchange(ownerSession, HttpMethod.GET, path(fixture.viewer().getId()), null);
        assertThat(emptyList.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(emptyList.getBody()).isEqualTo("[]");

        var createResponse = exchange(
                ownerSession,
                HttpMethod.POST,
                path(fixture.viewer().getId()),
                Map.of("issuer", "https://idp.example.org", "subject", "sub-http-1"));
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String createdId = extractField(createResponse.getBody(), "id");
        assertThat(createdId).isNotBlank();

        var listAfterCreate =
                exchange(ownerSession, HttpMethod.GET, path(fixture.viewer().getId()), null);
        assertThat(listAfterCreate.getBody()).contains("sub-http-1").contains("\"active\":true");

        var unlinkResponse =
                exchange(ownerSession, HttpMethod.DELETE, path(fixture.viewer().getId()) + "/" + createdId, null);
        assertThat(unlinkResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        var listAfterUnlink =
                exchange(ownerSession, HttpMethod.GET, path(fixture.viewer().getId()), null);
        assertThat(listAfterUnlink.getBody()).contains("\"active\":false");
    }

    private static String path(UUID userId) {
        return "/api/v1/users/" + userId + "/external-identities";
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

    private Fixture fixture() {
        Organization organization =
                organizationService.ensureOrganizationExists("ExtId Controller Test Org " + UUID.randomUUID());
        java.util.Map<OrganizationRole, User> usersByRole = new java.util.EnumMap<>(OrganizationRole.class);
        java.util.Map<OrganizationRole, AuthenticatedSession> sessionsByRole =
                new java.util.EnumMap<>(OrganizationRole.class);
        for (OrganizationRole role : OrganizationRole.values()) {
            User user = createUser(organization, role.name().toLowerCase(java.util.Locale.ROOT), role);
            usersByRole.put(role, user);
            sessionsByRole.put(role, PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD));
        }
        return new Fixture(usersByRole, sessionsByRole);
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
            java.util.Map<OrganizationRole, User> usersByRole,
            java.util.Map<OrganizationRole, AuthenticatedSession> sessionsByRole) {

        User viewer() {
            return usersByRole.get(OrganizationRole.VIEWER);
        }

        AuthenticatedSession sessionFor(OrganizationRole role) {
            return sessionsByRole.get(role);
        }
    }
}
