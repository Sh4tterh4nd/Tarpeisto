package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Persistent browser credentials must not retain removed account permissions. */
class PersistentSessionIntegrationTests extends AbstractIntegrationTest {
    private static final String PASSWORD = "CorrectHorseBattery1";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void loginSetsPersistentHardenedCookieAndThirtyDayIdleWindow() {
        User user = owner();
        var response = PermissionTestSupport.attemptLogin(rest, user.getUsername(), PASSWORD);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE))
                .anySatisfy(cookie -> assertThat(cookie)
                        .startsWith("SESSION=")
                        .contains("Max-Age=2592000", "HttpOnly", "Secure", "SameSite=Lax"));
        assertThat(jdbc.sql("SELECT max_inactive_interval FROM spring_session WHERE principal_name = :name")
                        .param("name", user.getUsername())
                        .query(Integer.class)
                        .single())
                .isEqualTo(Duration.ofDays(30).toSeconds());
    }

    @Test
    void sameCookieRecoversAfterTwoHoursWithoutAnAccessOrBackgroundRefresh() {
        User user = owner();
        var session = PermissionTestSupport.login(rest, user.getUsername(), PASSWORD);
        long lastAccess = clock.millis() - Duration.ofHours(2).toMillis();
        jdbc.sql("""
                UPDATE spring_session SET last_access_time = :lastAccess,
                    expiry_time = :lastAccess + CAST(max_inactive_interval AS BIGINT) * 1000
                WHERE principal_name = :name
                """)
                .param("lastAccess", lastAccess)
                .param("name", user.getUsername())
                .update();
        var reopened = get("/api/v1/session", session);
        assertThat(reopened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reopened.getBody()).contains(user.getId().toString());
    }

    @Test
    void demotionReplacesCachedRoleAndDeniesOwnerOperationsImmediately() {
        User user = owner();
        var session = PermissionTestSupport.login(rest, user.getUsername(), PASSWORD);
        assertThat(get("/api/v1/users", session).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.sql("UPDATE organization_membership SET role = 'VIEWER' WHERE user_id = :id")
                .param("id", user.getId())
                .update();
        var refreshed = get("/api/v1/session", session);
        assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(refreshed.getBody()).contains("\"role\":\"VIEWER\"");
        assertThat(get("/api/v1/users", session).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void disabledAccountCannotUseAnExistingSessionEvenWithoutSessionDeletion() {
        User user = owner();
        var session = PermissionTestSupport.login(rest, user.getUsername(), PASSWORD);
        jdbc.sql("UPDATE app_user SET enabled = FALSE WHERE id = :id")
                .param("id", user.getId())
                .update();
        assertThat(get("/api/v1/session", session).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/users", session).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void removedMembershipCannotUseAnExistingSession() {
        User user = owner();
        var session = PermissionTestSupport.login(rest, user.getUsername(), PASSWORD);
        jdbc.sql("DELETE FROM organization_membership WHERE user_id = :id")
                .param("id", user.getId())
                .update();
        assertThat(get("/api/v1/session", session).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get("/api/v1/users", session).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private org.springframework.http.ResponseEntity<String> get(
            String path, PermissionTestSupport.AuthenticatedSession session) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(session.headers()), String.class);
    }

    private User owner() {
        UUID organization = organizations
                .ensureOrganizationExists("Persistent session " + UUID.randomUUID())
                .getId();
        UUID id = UUID.randomUUID();
        User user = new User(
                id, "persistent-" + id, null, "Phone owner", passwords.encode(PASSWORD), true, clock.instant());
        users.save(user);
        memberships.save(new OrganizationMembership(
                UUID.randomUUID(), organization, id, OrganizationRole.OWNER, clock.instant()));
        return user;
    }
}
