package io.kellermann.bigcontainers.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.bigcontainers.AbstractIntegrationTest;
import io.kellermann.bigcontainers.config.LoginRateLimitProperties;
import io.kellermann.bigcontainers.model.Organization;
import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import io.kellermann.bigcontainers.security.PermissionTestSupport.AuthenticatedSession;
import io.kellermann.bigcontainers.service.OrganizationService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Covers the plan section 3.3 session/authentication security tests that apply to the non-OIDC
 * scope: session rotation, non-enumerating failures, disabled-user denial, and login rate
 * limiting.
 */
class SessionControllerIntegrationTests extends AbstractIntegrationTest {

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

    @Autowired
    private LoginRateLimitProperties rateLimitProperties;

    @Test
    void loginSucceedsAndReturnsThePrincipalDerivedFromTheServerSideMembership() {
        Organization organization = organization();
        User user = createUser(organization, "login-success-owner", OrganizationRole.OWNER);

        var response = PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), PASSWORD);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"userId\":\"" + user.getId() + "\"");
        assertThat(response.getBody()).contains("\"organizationId\":\"" + organization.getId() + "\"");
        assertThat(response.getBody()).contains("\"role\":\"OWNER\"");
    }

    @Test
    void successiveLoginsRotateTheSessionIdentifier() {
        Organization organization = organization();
        User user = createUser(organization, "rotation-owner", OrganizationRole.OWNER);

        AuthenticatedSession first = PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD);
        String firstSessionId = first.sessionCookieValue();
        assertThat(firstSessionId).isNotBlank();

        var logoutResponse = restTemplate.exchange(
                "/api/v1/session", HttpMethod.DELETE, new HttpEntity<>(first.headers()), Void.class);
        assertThat(logoutResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        AuthenticatedSession second = PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD);
        String secondSessionId = second.sessionCookieValue();

        assertThat(secondSessionId).isNotBlank().isNotEqualTo(firstSessionId);
    }

    @Test
    void getCurrentSessionRequiresAuthentication() {
        var response = restTemplate.getForEntity("/api/v1/session", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void getCurrentSessionReturnsThePrincipalWhenAuthenticated() {
        Organization organization = organization();
        User user = createUser(organization, "whoami-owner", OrganizationRole.OWNER);
        AuthenticatedSession session = PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD);

        var response = restTemplate.exchange(
                "/api/v1/session", HttpMethod.GET, new HttpEntity<>(session.headers()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"userId\":\"" + user.getId() + "\"");
    }

    @Test
    void logoutInvalidatesTheSession() {
        Organization organization = organization();
        User user = createUser(organization, "logout-owner", OrganizationRole.OWNER);
        AuthenticatedSession session = PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD);

        restTemplate.exchange("/api/v1/session", HttpMethod.DELETE, new HttpEntity<>(session.headers()), Void.class);

        var afterLogout = restTemplate.exchange(
                "/api/v1/session", HttpMethod.GET, new HttpEntity<>(session.headers()), String.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongPasswordAndUnknownUsernameProduceIndistinguishableResponses() {
        Organization organization = organization();
        User user = createUser(organization, "enumeration-check-user", OrganizationRole.VIEWER);

        var wrongPasswordResponse =
                PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), "not-the-real-password");
        var unknownUserResponse = PermissionTestSupport.attemptLogin(
                restTemplate, "no-such-user-" + UUID.randomUUID(), "irrelevant-password");

        assertThat(wrongPasswordResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(unknownUserResponse.getStatusCode()).isEqualTo(wrongPasswordResponse.getStatusCode());
        assertThat(unknownUserResponse.getBody()).isEqualTo(wrongPasswordResponse.getBody());
    }

    @Test
    void disabledUserIsDeniedWithTheSameResponseAsInvalidCredentials() {
        Organization organization = organization();
        User user = createUser(organization, "disabled-user", OrganizationRole.VIEWER);
        user.disable(clock.instant());
        userRepository.save(user);

        var disabledUserResponse = PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), PASSWORD);
        var wrongPasswordResponse = PermissionTestSupport.attemptLogin(
                restTemplate, "no-such-user-" + UUID.randomUUID(), "irrelevant-password");

        assertThat(disabledUserResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(disabledUserResponse.getBody()).isEqualTo(wrongPasswordResponse.getBody());
    }

    @Test
    void loginRateLimitingTriggersAfterRepeatedFailuresForTheSameKey() {
        Organization organization = organization();
        User user = createUser(organization, "rate-limited-user", OrganizationRole.VIEWER);

        for (int attempt = 0; attempt < rateLimitProperties.maxAttempts(); attempt++) {
            var response = PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), "wrong-password");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        var rateLimitedResponse =
                PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), "wrong-password");

        assertThat(rateLimitedResponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rateLimitedResponse.getBody()).contains("\"errorCode\":\"RATE_LIMIT_EXCEEDED\"");
    }

    @Test
    void aSuccessfulLoginResetsTheRateLimitCounterForItsKey() {
        Organization organization = organization();
        User user = createUser(organization, "reset-after-success-user", OrganizationRole.VIEWER);

        PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), "wrong-password");
        PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), "wrong-password");
        AuthenticatedSession session = PermissionTestSupport.login(restTemplate, user.getUsername(), PASSWORD);
        assertThat(session.sessionCookieValue()).isNotBlank();

        // The counter was reset by the successful login above, so further failed attempts start
        // from zero again rather than immediately hitting the limit left over from before.
        var response = PermissionTestSupport.attemptLogin(restTemplate, user.getUsername(), "wrong-password");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private Organization organization() {
        return organizationService.ensureOrganizationExists("Session Test Org " + UUID.randomUUID());
    }

    private User createUser(Organization organization, String username, OrganizationRole role) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                username + "-" + UUID.randomUUID(),
                null,
                "Test User " + username,
                passwordEncoder.encode(PASSWORD),
                true,
                now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }
}
