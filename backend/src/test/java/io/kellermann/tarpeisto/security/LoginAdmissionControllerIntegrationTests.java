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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(
        properties = {
            "tarpeisto.security.login-rate-limit.max-attempts=2",
            "tarpeisto.security.login-rate-limit.max-client-attempts=4"
        })
class LoginAdmissionControllerIntegrationTests extends AbstractIntegrationTest {
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

    @Test
    void successfulLoginSpendsPairAndClientBudgetAndRotationGetsGeneric429() {
        var org = organizations.ensureOrganizationExists("Admission " + UUID.randomUUID());
        String name = "admission-" + UUID.randomUUID();
        var user = users.save(
                new User(UUID.randomUUID(), name, null, "Owner", passwords.encode("correct"), true, clock.instant()));
        memberships.save(new OrganizationMembership(
                UUID.randomUUID(), org.getId(), user.getId(), OrganizationRole.OWNER, clock.instant()));
        assertThat(PermissionTestSupport.attemptLogin(http, name, "wrong").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(PermissionTestSupport.attemptLogin(http, name, "correct").getStatusCode())
                .isEqualTo(HttpStatus.OK);
        var pairDenied = PermissionTestSupport.attemptLogin(
                http, " " + name.toUpperCase(java.util.Locale.ROOT) + " ", "correct");
        assertThat(pairDenied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(PermissionTestSupport.attemptLogin(http, "missing-1", "irrelevant")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(PermissionTestSupport.attemptLogin(http, "missing-2", "irrelevant")
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        var rotatedDenied = PermissionTestSupport.attemptLogin(http, "missing-3", "irrelevant");
        assertThat(rotatedDenied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(rotatedDenied.getBody()).isEqualTo(pairDenied.getBody()).contains("RATE_LIMIT_EXCEEDED");
        assertThat(rotatedDenied.getBody()).doesNotContain(name, "missing-3", "correct", "irrelevant");
    }
}
