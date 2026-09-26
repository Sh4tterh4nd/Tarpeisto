package io.kellermann.bigcontainers.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Shared permission/session test helper (implementation plan section 3.2: "Shared permission test
 * helpers"). {@link TestRestTemplate} does not persist cookies across requests by itself, so this
 * hand-rolls the minimal cookie jar a same-origin SPA client would otherwise get from the browser:
 * prime the CSRF cookie, log in while presenting it, and expose headers that carry the rotated
 * session cookie plus a valid CSRF header for subsequent authenticated calls.
 *
 * <p>Not itself a test class (no {@code @Test} methods, and its name does not end in {@code
 * Tests}), so it is not picked up as a test by the Gradle test task - the same pattern {@code
 * AbstractIntegrationTest} already uses.
 */
public final class PermissionTestSupport {

    private static final Pattern COOKIE_NAME_VALUE = Pattern.compile("^([^=]+)=([^;]*)");

    private PermissionTestSupport() {}

    /**
     * Logs in as {@code username}/{@code password} and returns the resulting authenticated
     * session, or throws {@link LoginFailedException} if the login endpoint does not answer
     * {@code 200 OK}. Any earlier session cookie is deliberately discarded here so the caller can
     * assert on session-identifier rotation by comparing {@link
     * AuthenticatedSession#sessionCookieValue()} before and after re-authenticating.
     */
    public static AuthenticatedSession login(TestRestTemplate restTemplate, String username, String password) {
        CookieJar primed = primedCookieJar(restTemplate);
        ResponseEntity<String> response = postLogin(restTemplate, primed, username, password);
        if (!response.getStatusCode().equals(HttpStatus.OK)) {
            throw new LoginFailedException(username, response.getStatusCode());
        }
        return new AuthenticatedSession(primed.mergeFrom(response.getHeaders()));
    }

    /**
     * Performs the raw {@code POST /api/v1/session} call (correctly primed with a CSRF cookie/
     * header, like a real SPA client) without asserting on the outcome, so a caller can inspect a
     * deliberately failing login (wrong password, unknown user, disabled account, rate limiting).
     */
    public static ResponseEntity<String> attemptLogin(TestRestTemplate restTemplate, String username, String password) {
        return postLogin(restTemplate, primedCookieJar(restTemplate), username, password);
    }

    private static ResponseEntity<String> postLogin(
            TestRestTemplate restTemplate, CookieJar primed, String username, String password) {
        HttpHeaders loginHeaders = new HttpHeaders();
        loginHeaders.setContentType(MediaType.APPLICATION_JSON);
        primed.applyTo(loginHeaders);

        HttpEntity<Map<String, String>> requestEntity =
                new HttpEntity<>(Map.of("username", username, "password", password), loginHeaders);
        return restTemplate.postForEntity("/api/v1/session", requestEntity, String.class);
    }

    private static CookieJar primedCookieJar(TestRestTemplate restTemplate) {
        return CookieJar.EMPTY.mergeFrom(
                restTemplate.getForEntity("/api/v1/application", String.class).getHeaders());
    }

    /** A logged-in session's cookies, ready to be attached to further {@link TestRestTemplate} calls. */
    public static final class AuthenticatedSession {

        private final CookieJar cookieJar;

        private AuthenticatedSession(CookieJar cookieJar) {
            this.cookieJar = cookieJar;
        }

        /** Headers suitable for any request (GET or a mutation) made with this session. */
        public HttpHeaders headers() {
            HttpHeaders headers = new HttpHeaders();
            cookieJar.applyTo(headers);
            return headers;
        }

        public String sessionCookieValue() {
            // Spring Session overrides the servlet container's default JSESSIONID cookie name
            // with "SESSION" (DefaultCookieSerializer's default), which is what is actually set
            // here once spring-boot-starter-session-jdbc is on the classpath.
            return cookieJar.cookies.get("SESSION");
        }
    }

    public static final class LoginFailedException extends RuntimeException {
        public LoginFailedException(String username, org.springframework.http.HttpStatusCode status) {
            super("Login as '" + username + "' failed with status " + status);
        }
    }

    /** Minimal accumulating cookie jar: later Set-Cookie values for the same name replace earlier ones. */
    private static final class CookieJar {

        static final CookieJar EMPTY = new CookieJar(Map.of());

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
            String csrfToken = cookies.get("XSRF-TOKEN");
            if (csrfToken != null) {
                headers.add("X-XSRF-TOKEN", csrfToken);
            }
        }
    }
}
