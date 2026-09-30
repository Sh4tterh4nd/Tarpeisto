package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.TrackingMode;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.service.AssetModelService;
import io.kellermann.tarpeisto.service.AssetService;
import io.kellermann.tarpeisto.service.OrganizationService;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
        properties = {
            "tarpeisto.security.asset-code-rate-limit.max-attempts=4",
            "tarpeisto.security.asset-code-rate-limit.max-client-attempts=20"
        })
class AssetCodeAdmissionIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private TestRestTemplate http;

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
    private AssetModelService models;

    @Autowired
    private AssetService assets;

    @Autowired
    private io.kellermann.tarpeisto.service.BookingService bookings;

    @Autowired
    private io.kellermann.tarpeisto.service.TemporaryAccessService temporaryAccess;

    @Autowired
    private org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions;

    @Test
    void viewerHitMalformedMissingAndForeignEachSpendOneBudgetAndHeadCannotBypassIt() {
        var owner = owner();
        var foreign = owner();
        var ownCode = assetCode(owner);
        var foreignCode = assetCode(foreign);
        var viewer = users.save(new User(
                UUID.randomUUID(),
                "viewer-" + UUID.randomUUID(),
                null,
                "Viewer",
                passwords.encode("correct"),
                true,
                clock.instant()));
        memberships.save(new OrganizationMembership(
                UUID.randomUUID(), owner.organizationId(), viewer.getId(), OrganizationRole.VIEWER, clock.instant()));
        var session = PermissionTestSupport.login(http, viewer.getUsername(), "correct");
        String base = "/api/v1/assets/by-code/";
        // Anonymous probes must fail before spending the authenticated lookup budget.
        for (int i = 0; i < 6; i++)
            assertThat(http.getForEntity(base + ownCode, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(get(session, base + ownCode, HttpMethod.GET).getStatusCode()).isEqualTo(HttpStatus.OK);
        var malformed = get(session, base + "invalid", HttpMethod.GET);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // HEAD invokes the same MVC lookup and must use the same budget.
        assertThat(get(session, base + foreignCode, HttpMethod.HEAD).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        var missing = get(
                session,
                base + io.kellermann.tarpeisto.model.AssetCode.format("ZZZZZ").value(),
                HttpMethod.GET);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var denied = get(session, base + ownCode, HttpMethod.GET);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getBody()).contains("RATE_LIMIT_EXCEEDED").doesNotContain(ownCode, foreignCode);
        assertThat(get(session, base + ownCode, HttpMethod.HEAD).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void temporaryDenialPrecedesLookupAdmissionAndCannotExhaustPermanentClientBudget() {
        var owner = owner();
        String code = assetCode(owner);
        var booking = bookings.create(
                owner,
                UUID.randomUUID(),
                "Assignment",
                null,
                null,
                null,
                clock.instant(),
                clock.instant().plusSeconds(3600));
        var invitation = temporaryAccess.create(owner, booking.id(), null);
        var volunteer = temporaryAccess.redeem(invitation.token(), "Named helper", UUID.randomUUID());
        String sessionId = temporarySession(sessions, volunteer);
        var headers = new org.springframework.http.HttpHeaders();
        headers.add(
                "Cookie",
                "SESSION="
                        + java.util.Base64.getEncoder()
                                .encodeToString(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        for (int i = 0; i < 25; i++) {
            var response = http.exchange(
                    "/api/v1/assets/by-code/" + code,
                    i % 2 == 0 ? HttpMethod.GET : HttpMethod.HEAD,
                    new HttpEntity<>(headers),
                    String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        var permanent = PermissionTestSupport.login(http, owner.username(), "correct");
        for (int i = 0; i < 4; i++)
            assertThat(get(permanent, "/api/v1/assets/by-code/" + code, HttpMethod.GET)
                            .getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        assertThat(get(permanent, "/api/v1/assets/by-code/" + code, HttpMethod.GET)
                        .getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    private <S extends org.springframework.session.Session> String temporarySession(
            org.springframework.session.FindByIndexNameSessionRepository<S> repository, TarpeistoPrincipal principal) {
        S session = repository.createSession();
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("TEMPORARY_AUDITOR"))));
        session.setAttribute("SPRING_SECURITY_CONTEXT", context);
        repository.save(session);
        return session.getId();
    }

    private org.springframework.http.ResponseEntity<String> get(
            PermissionTestSupport.AuthenticatedSession session, String path, HttpMethod method) {
        return http.exchange(path, method, new HttpEntity<>(session.headers()), String.class);
    }

    private String assetCode(TarpeistoPrincipal owner) {
        var model = models.create(
                owner, "Unit " + UUID.randomUUID(), null, null, null, TrackingMode.SERIALIZED_ASSET, null, null, false);
        return assets.create(owner, model.id(), "Unit", null, List.of()).publicCode();
    }

    private TarpeistoPrincipal owner() {
        var org = organizations.ensureOrganizationExists("Code admission " + UUID.randomUUID());
        var user = users.save(new User(
                UUID.randomUUID(),
                "owner-" + UUID.randomUUID(),
                null,
                "Owner",
                passwords.encode("correct"),
                true,
                clock.instant()));
        memberships.save(new OrganizationMembership(
                UUID.randomUUID(), org.getId(), user.getId(), OrganizationRole.OWNER, clock.instant()));
        return new TarpeistoPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), org.getId(), OrganizationRole.OWNER);
    }
}
