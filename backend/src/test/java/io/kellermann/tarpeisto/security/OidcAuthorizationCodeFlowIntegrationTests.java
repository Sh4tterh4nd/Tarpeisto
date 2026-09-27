package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.ExternalIdentity;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ExternalIdentityRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import no.nav.security.mock.oauth2.MockOAuth2Server;
import no.nav.security.mock.oauth2.token.DefaultOAuth2TokenCallback;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

/**
 * The full OIDC authorization-code exchange, end to end, against a real (embedded, not mocked-out)
 * OpenID Connect server: discovery, the browser redirect dance, the server-to-server code
 * exchange, ID token validation, and the resulting Tarpeisto session (specification section
 * 29 acceptance scenario 23; implementation plan section 3.3).
 *
 * <p><strong>Why an embedded {@code MockOAuth2Server} instead of the {@code
 * ghcr.io/navikt/mock-oauth2-server} Docker image</strong> (which this task did verify is
 * anonymously pullable): the same underlying library also ships as a plain JVM test dependency
 * with a programmatic {@code enqueueCallback} API for setting the exact subject/claims of the
 * *next* token response. The Docker image's only non-interactive per-request customization
 * mechanism is a static {@code JSON_CONFIG} of token-request-parameter matchers, which cannot
 * vary per test scenario without restarting the container between scenarios (there is no request
 * parameter available at the token endpoint carrying test-specific data). The embedded form drives
 * the exact same real HTTP authorization-code exchange, discovery document, and JWKS-verified ID
 * token, with full per-test control. It is started once, is never stopped explicitly (mirroring
 * {@link AbstractIntegrationTest}'s singleton-container pattern for PostgreSQL), and needs no
 * Docker networking.
 *
 * <p>Redirects are followed <strong>manually</strong> (a plain {@link RestTemplate} with automatic
 * redirect-following disabled) rather than relying on an HTTP client's built-in redirect handling,
 * because the flow crosses two different origins (this application and the mock provider) and each
 * hop's cookies/{@code Location} must be inspected and selectively carried forward exactly the way
 * a real browser would, not merged automatically.
 */
class OidcAuthorizationCodeFlowIntegrationTests extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "tarpeisto-test";
    private static final String ISSUER_ID = "default";
    private static final MockOAuth2Server MOCK_OAUTH2_SERVER = new MockOAuth2Server();

    static {
        MOCK_OAUTH2_SERVER.start();
    }

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        registry.add("tarpeisto.authentication.mode", () -> "LOCAL_AND_OIDC");
        registry.add("tarpeisto.authentication.oidc.display-name", () -> "Mock Provider");
        registry.add(
                "tarpeisto.authentication.oidc.issuer-uri",
                () -> MOCK_OAUTH2_SERVER.issuerUrl(ISSUER_ID).toString());
        registry.add("tarpeisto.authentication.oidc.client-id", () -> CLIENT_ID);
        registry.add("tarpeisto.authentication.oidc.client-secret", () -> "test-secret");
        registry.add("tarpeisto.authentication.oidc.scopes", () -> "openid,profile,email");
    }

    @LocalServerPort
    private int appPort;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private ExternalIdentityRepository identityRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    @Test
    void aPreLinkedIdentityEstablishesTheSameKindOfSessionAsLocalLoginWithIdenticalCsrfEnforcement() {
        Organization organization = organizationService.ensureOrganizationExists("OIDC E2E Org " + UUID.randomUUID());
        // Owner, and the mutation below targets this same user with an already-true value, so a
        // successful (CSRF-header-present) call is unambiguously a 204 - never blocked by role,
        // isolating the CSRF assertion from any role/last-Owner-guard concern.
        User user = createUser(organization, true, OrganizationRole.OWNER);
        String subject = "subject-" + UUID.randomUUID();
        linkIdentity(user, subject);

        MOCK_OAUTH2_SERVER.enqueueCallback(new DefaultOAuth2TokenCallback(
                ISSUER_ID,
                subject,
                JOSEObjectType.JWT.getType(),
                List.of(CLIENT_ID),
                Map.of("email", "returning@example.org", "email_verified", true, "name", "Returning User"),
                3600));

        CookieJar sessionCookies = driveAuthorizationCodeFlow();

        // Spring Security's CsrfAuthenticationStrategy deliberately invalidates the pre-login
        // CSRF token on successful authentication (a session-fixation-style protection); the
        // rotated token only becomes visible on this next response's Set-Cookie header, so the
        // jar is refreshed from every subsequent response - exactly what a real browser's
        // persistent cookie jar would do.
        ResponseEntity<String> sessionResponse = appGet("/api/v1/session", sessionCookies);
        sessionCookies = sessionCookies.mergeFrom(sessionResponse.getHeaders());
        assertThat(sessionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(sessionResponse.getBody())
                .contains(user.getUsername())
                .contains(user.getId().toString());

        // CSRF parity (implementation plan section 3.3: "Local and OIDC sessions receive
        // identical CSRF and authorization enforcement"): a mutation without the CSRF header is
        // rejected (403, the same CsrfException-is-an-AccessDeniedException shape local sessions
        // get) even though the session itself is authenticated...
        ResponseEntity<String> withoutCsrf = appExchange(
                HttpMethod.PUT,
                "/api/v1/users/" + user.getId() + "/enabled",
                sessionCookies,
                false,
                Map.of("enabled", true));
        sessionCookies = sessionCookies.mergeFrom(withoutCsrf.getHeaders());
        assertThat(withoutCsrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(withoutCsrf.getBody()).contains("\"errorCode\":\"ACCESS_DENIED\"");

        // ...and succeeds once the CSRF header is presented, exactly like a local-login session.
        ResponseEntity<String> withCsrf = appExchange(
                HttpMethod.PUT,
                "/api/v1/users/" + user.getId() + "/enabled",
                sessionCookies,
                true,
                Map.of("enabled", true));
        assertThat(withCsrf.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void aDisabledLocalUserIsDeniedAfterSuccessfulProviderAuthentication() {
        Organization organization = organizationService.ensureOrganizationExists("OIDC E2E Org " + UUID.randomUUID());
        User user = createUser(organization, false, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();
        linkIdentity(user, subject);

        MOCK_OAUTH2_SERVER.enqueueCallback(new DefaultOAuth2TokenCallback(
                ISSUER_ID,
                subject,
                JOSEObjectType.JWT.getType(),
                List.of(CLIENT_ID),
                Map.of("email", "disabled@example.org", "email_verified", true, "name", "Disabled User"),
                3600));

        CookieJar cookiesAfterCallback = driveAuthorizationCodeFlowExpectingFailure();

        // Authentication failed before any session was established for this user: the callback
        // redirect's session cookie (if any) does not resolve to an authenticated principal.
        ResponseEntity<String> sessionResponse = appGet("/api/v1/session", cookiesAfterCallback);
        assertThat(sessionResponse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // -------------------------------------------------------------------------------------
    // Manual authorization-code flow driver
    // -------------------------------------------------------------------------------------

    private CookieJar driveAuthorizationCodeFlow() {
        return drive(true);
    }

    private CookieJar driveAuthorizationCodeFlowExpectingFailure() {
        return drive(false);
    }

    private CookieJar drive(boolean expectSuccess) {
        RestTemplate noRedirectClient = noRedirectClient();

        // 1. Browser navigates to our app's OIDC entry point.
        ResponseEntity<Void> initiate = noRedirectClient.exchange(
                appUri("/oauth2/authorization/"
                        + io.kellermann.tarpeisto.config.AuthenticationProperties.OIDC_REGISTRATION_ID),
                HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                Void.class);
        assertThat(initiate.getStatusCode().is3xxRedirection()).isTrue();
        CookieJar preAuthSessionCookies = CookieJar.EMPTY.mergeFrom(initiate.getHeaders());
        URI providerAuthorizeUrl = initiate.getHeaders().getLocation();
        assertThat(providerAuthorizeUrl).isNotNull();

        // 2. Browser follows the redirect to the mock provider's /authorize endpoint. Library-mode
        // MockOAuth2Server defaults interactiveLogin=false, so this immediately redirects back
        // with an authorization code - no login page to drive.
        ResponseEntity<Void> authorize = noRedirectClient.exchange(
                providerAuthorizeUrl, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), Void.class);
        assertThat(authorize.getStatusCode().is3xxRedirection()).isTrue();
        URI callbackUrl = authorize.getHeaders().getLocation();
        assertThat(callbackUrl).isNotNull();

        // 3. Browser follows the redirect back to our app's callback. This carries the
        // pre-auth session cookie from step 1, needed to validate the OAuth2 "state"/PKCE
        // verifier our app itself generated and stored server-side.
        HttpHeaders callbackHeaders = new HttpHeaders();
        preAuthSessionCookies.applyTo(callbackHeaders);
        ResponseEntity<Void> callback =
                noRedirectClient.exchange(callbackUrl, HttpMethod.GET, new HttpEntity<>(callbackHeaders), Void.class);

        if (expectSuccess) {
            assertThat(callback.getStatusCode().is3xxRedirection()).isTrue();
        }
        return preAuthSessionCookies.mergeFrom(callback.getHeaders());
    }

    // -------------------------------------------------------------------------------------
    // HTTP plumbing
    // -------------------------------------------------------------------------------------

    private URI appUri(String path) {
        return URI.create("http://localhost:" + appPort + path);
    }

    private ResponseEntity<String> appGet(String path, CookieJar cookies) {
        HttpHeaders headers = new HttpHeaders();
        cookies.applyTo(headers);
        return noRedirectClient().exchange(appUri(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> appExchange(
            HttpMethod method, String path, CookieJar cookies, boolean includeCsrfHeader, Object body) {
        HttpHeaders headers = new HttpHeaders();
        cookies.applyTo(headers);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        if (includeCsrfHeader) {
            String csrfToken = cookies.cookies.get("XSRF-TOKEN");
            if (csrfToken != null) {
                headers.add("X-XSRF-TOKEN", csrfToken);
            }
        }
        return noRedirectClient().exchange(appUri(path), method, new HttpEntity<>(body, headers), String.class);
    }

    private static RestTemplate noRedirectClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate restTemplate = new RestTemplate(factory);
        // Unlike TestRestTemplate, a plain RestTemplate's default error handler throws on every
        // 4xx/5xx response. These tests assert on status codes themselves (including expected
        // 401/403 denials), so error responses must be returned normally instead.
        restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return restTemplate;
    }

    private void linkIdentity(User user, String subject) {
        String issuer = MOCK_OAUTH2_SERVER.issuerUrl(ISSUER_ID).toString();
        identityRepository.save(
                new ExternalIdentity(UUID.randomUUID(), user.getId(), issuer, subject, null, null, clock.instant()));
    }

    private User createUser(Organization organization, boolean enabled, OrganizationRole role) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                "user-" + UUID.randomUUID(),
                null,
                "Test User",
                passwordEncoder.encode("CorrectHorseBattery1"),
                enabled,
                now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }

    /** Minimal accumulating cookie jar: later Set-Cookie values for the same name replace earlier ones. */
    private static final class CookieJar {

        static final CookieJar EMPTY = new CookieJar(Map.of());

        private static final Pattern COOKIE_NAME_VALUE = Pattern.compile("^([^=]+)=([^;]*)");

        private final Map<String, String> cookies;

        private CookieJar(Map<String, String> cookies) {
            this.cookies = cookies;
        }

        CookieJar mergeFrom(HttpHeaders headers) {
            Map<String, String> merged = new LinkedHashMap<>(cookies);
            for (String setCookie : headers.getOrEmpty(HttpHeaders.SET_COOKIE)) {
                Matcher matcher = COOKIE_NAME_VALUE.matcher(setCookie);
                if (matcher.find()) {
                    merged.put(matcher.group(1), matcher.group(2));
                }
            }
            return new CookieJar(merged);
        }

        void applyTo(HttpHeaders headers) {
            if (cookies.isEmpty()) {
                return;
            }
            StringBuilder cookieHeader = new StringBuilder();
            cookies.forEach((name, value) -> {
                if (!cookieHeader.isEmpty()) {
                    cookieHeader.append("; ");
                }
                cookieHeader.append(name).append('=').append(value);
            });
            headers.add(HttpHeaders.COOKIE, cookieHeader.toString());
        }
    }
}
