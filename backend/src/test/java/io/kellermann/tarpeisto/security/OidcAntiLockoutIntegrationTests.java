package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.config.AuthenticationProperties;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The anti-lockout guarantee (ADR-0003, implementation plan section 3.3: "Provider failure does
 * not prevent an enabled local Owner from signing in") - the single most important OIDC test in
 * this task.
 *
 * <p>{@code LOCAL_AND_OIDC} mode is configured with a syntactically valid but genuinely
 * unreachable issuer URI (loopback port 1, which refuses connections immediately). Application
 * startup itself must still succeed (the properties are non-blank, so {@code
 * AuthenticationProperties}'s fail-fast validation passes; discovery is deliberately lazy - see
 * {@code LazyOidcClientRegistrationRepository}), and local login for an enabled Owner must keep
 * working before, during, and after an OIDC login attempt fails against the unreachable provider.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "tarpeisto.authentication.mode=LOCAL_AND_OIDC",
            "tarpeisto.authentication.oidc.display-name=Unreachable Provider",
            "tarpeisto.authentication.oidc.issuer-uri=http://127.0.0.1:1/unreachable-issuer",
            "tarpeisto.authentication.oidc.client-id=broken-client",
            "tarpeisto.authentication.oidc.client-secret=broken-secret",
            "tarpeisto.authentication.oidc.scopes=openid,profile,email"
        })
class OidcAntiLockoutIntegrationTests extends AbstractIntegrationTest {

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

    @Test
    void anEnabledLocalOwnerCanStillSignInWhileTheConfiguredOidcProviderIsUnreachable() {
        Organization organization =
                organizationService.ensureOrganizationExists("Anti-Lockout Org " + UUID.randomUUID());
        String username = "owner-" + UUID.randomUUID();
        createOwner(organization, username);

        var loginAttempt = PermissionTestSupport.attemptLogin(restTemplate, username, PASSWORD);

        assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anOidcLoginAttemptFailingAgainstTheUnreachableProviderDoesNotAffectALaterLocalLogin() {
        Organization organization =
                organizationService.ensureOrganizationExists("Anti-Lockout Org " + UUID.randomUUID());
        String username = "owner-" + UUID.randomUUID();
        createOwner(organization, username);

        // Starting an OIDC login fails (the provider is unreachable) - this must not be a
        // fatal, application-wide condition. It is scoped to this one request/attempt.
        var oidcAttempt = restTemplate.getForEntity(
                "/oauth2/authorization/" + AuthenticationProperties.OIDC_REGISTRATION_ID, String.class);
        assertThat(oidcAttempt.getStatusCode().is2xxSuccessful()
                        || oidcAttempt.getStatusCode().is5xxServerError())
                .isTrue();

        var loginAttempt = PermissionTestSupport.attemptLogin(restTemplate, username, PASSWORD);
        assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Regression test for the defect an earlier review found: {@code GET
     * /oauth2/authorization/oidc} against an unreachable provider must fail as the same RFC 9457
     * {@code application/problem+json} shape every other error path in this API returns - not
     * Spring Boot's default {@code /error} body ({@code {"timestamp":...,"status":500,"error":
     * "Internal Server Error","path":"/oauth2/authorization/oidc"}}) - and the response must tell
     * the caller local sign-in still works, without leaking the (unreachable) issuer URI, client
     * id, or any exception/connection detail.
     */
    @Test
    void theOidcEntryPointReturnsAnActionableProblemDetailsResponseWhenTheProviderIsUnreachable() {
        var response = restTemplate.getForEntity(
                "/oauth2/authorization/" + AuthenticationProperties.OIDC_REGISTRATION_ID, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        var contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        assertThat(response.getBody())
                .isNotNull()
                .contains("\"errorCode\":\"OIDC_PROVIDER_UNAVAILABLE\"")
                .containsIgnoringCase("local")
                .containsIgnoringCase("available")
                // Must not leak provider internals (issuer/host, client credentials, or the
                // underlying exception/stack trace) into the client-facing body. "connect" is
                // deliberately not checked here: the title legitimately contains "OpenID
                // Connect" - the protocol name, not a leaked connection detail.
                .doesNotContain("127.0.0.1")
                .doesNotContain("unreachable-issuer")
                .doesNotContain("broken-client")
                .doesNotContain("broken-secret")
                .doesNotContainIgnoringCase("IllegalArgumentException")
                .doesNotContainIgnoringCase("stack_trace")
                .doesNotContainIgnoringCase("java.lang");

        // The outage is scoped to this one request: local login is unaffected afterward too.
        Organization organization =
                organizationService.ensureOrganizationExists("Anti-Lockout Org " + UUID.randomUUID());
        String username = "owner-" + UUID.randomUUID();
        createOwner(organization, username);
        var loginAttempt = PermissionTestSupport.attemptLogin(restTemplate, username, PASSWORD);
        assertThat(loginAttempt.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private void createOwner(Organization organization, String username) {
        var now = clock.instant();
        User owner = new User(UUID.randomUUID(), username, null, "Owner", passwordEncoder.encode(PASSWORD), true, now);
        userRepository.save(owner);
        membershipRepository.save(new OrganizationMembership(
                UUID.randomUUID(), organization.getId(), owner.getId(), OrganizationRole.OWNER, now));
    }
}
