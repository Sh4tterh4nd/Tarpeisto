package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import io.kellermann.tarpeisto.model.ExternalIdentity;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ExternalIdentityRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.PermissionTestSupport;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/** Reusing an inactive global identity must authorize both its previous and proposed account. */
class ExternalIdentityRelinkingIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private ExternalIdentityService links;

    @Autowired
    private ExternalIdentityRepository identities;

    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private Clock clock;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void foreignInactiveIdentityHttpDenialDoesNotExposeClaimsOrMutateHistory() {
        var caller = owner();
        var foreign = owner();
        var identity = inactive(foreign.userId());
        var before = snapshot(identity);
        var session = PermissionTestSupport.login(http, caller.username(), "correct");
        long activity = activityCount();
        var headers = session.headers();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity(
                "/api/v1/users/" + caller.userId() + "/external-identities",
                new HttpEntity<>(Map.of("issuer", identity.getIssuer(), "subject", identity.getSubject()), headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .contains("External identity not found.", "NOT_FOUND")
                .doesNotContain(
                        identity.getLastEmail(),
                        identity.getLastDisplayName(),
                        identity.getLastLoginAt().toString(),
                        foreign.userId().toString(),
                        identity.getId().toString());
        assertThat(snapshot(identities.findById(identity.getId()).orElseThrow()))
                .isEqualTo(before);
        assertThat(activityCount()).isEqualTo(activity);
    }

    @Test
    @Transactional
    void inactiveIdentityOfAPreviousSharedUserCannotBeReassignedEvenToAnOwnedLocalAccount() {
        var caller = owner();
        var foreign = owner();
        var previous = user(caller.organizationId(), OrganizationRole.VIEWER);
        memberships.saveAndFlush(new OrganizationMembership(
                UUID.randomUUID(),
                foreign.organizationId(),
                previous.getId(),
                OrganizationRole.OWNER,
                clock.instant()));
        var identity = inactive(previous.getId());
        var before = snapshot(identity);
        long activity = activityCount();
        assertThatThrownBy(() -> links.createLink(caller, caller.userId(), identity.getIssuer(), identity.getSubject()))
                .isInstanceOf(ArchiveConflictException.class)
                .hasMessageContaining("another organization");
        entityManager.flush();
        entityManager.clear();
        assertThat(snapshot(identities.findById(identity.getId()).orElseThrow()))
                .isEqualTo(before);
        assertThat(activityCount()).isEqualTo(activity);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional
    void ownedInactiveReactivationOrReassignmentPersistsTheControlledUserAssociation(boolean reassign) {
        var caller = owner();
        var previous = user(caller.organizationId(), OrganizationRole.VIEWER);
        var target = reassign ? user(caller.organizationId(), OrganizationRole.OPERATOR_AUDITOR) : previous;
        var identity = inactive(previous.getId());
        var before = snapshot(identity);
        var response = links.createLink(caller, target.getId(), identity.getIssuer(), identity.getSubject());
        assertThat(response.id()).isEqualTo(identity.getId());
        assertThat(response.active()).isTrue();
        entityManager.flush();
        entityManager.clear();
        var persisted = identities.findById(identity.getId()).orElseThrow();
        assertThat(persisted.getUserId()).isEqualTo(target.getId());
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getIssuer()).isEqualTo(before.issuer());
        assertThat(persisted.getSubject()).isEqualTo(before.subject());
        assertThat(persisted.getCreatedAt()).isEqualTo(before.createdAt());
        assertThat(persisted.getLastEmail()).isEqualTo(before.email());
        assertThat(persisted.getLastDisplayName()).isEqualTo(before.displayName());
        assertThat(persisted.getLastLoginAt()).isEqualTo(before.lastLoginAt());
        assertThat(jdbc.sql("SELECT user_id FROM external_identity WHERE id=:id")
                        .param("id", identity.getId())
                        .query(UUID.class)
                        .single())
                .isEqualTo(target.getId());
        var login = links.resolveLogin(persisted.getIssuer(), persisted.getSubject(), null, false, "Returning user");
        assertThat(login).isInstanceOf(ExternalIdentityService.OidcLoginOutcome.Authenticated.class);
        var principal = ((ExternalIdentityService.OidcLoginOutcome.Authenticated) login).principal();
        assertThat(principal.userId()).isEqualTo(target.getId());
        assertThat(principal.organizationId()).isEqualTo(caller.organizationId());
        assertThat(principal.role()).isEqualTo(reassign ? OrganizationRole.OPERATOR_AUDITOR : OrganizationRole.VIEWER);
    }

    private ExternalIdentity inactive(UUID user) {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        var identity = new ExternalIdentity(
                UUID.randomUUID(),
                user,
                "https://inactive.example.org",
                UUID.randomUUID().toString(),
                "private@example.org",
                "Private previous profile",
                created);
        identity.recordLogin("private@example.org", "Private previous profile", created.plusSeconds(3600));
        identity.unlink(created.plusSeconds(7200));
        return identities.saveAndFlush(identity);
    }

    private TarpeistoPrincipal owner() {
        var org = organizations.ensureOrganizationExists("Relink " + UUID.randomUUID());
        var user = user(org.getId(), OrganizationRole.OWNER);
        return new TarpeistoPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), org.getId(), OrganizationRole.OWNER);
    }

    private User user(UUID org, OrganizationRole role) {
        var user = users.saveAndFlush(new User(
                UUID.randomUUID(),
                "relink-" + UUID.randomUUID(),
                null,
                "Local user",
                passwords.encode("correct"),
                true,
                clock.instant()));
        memberships.saveAndFlush(
                new OrganizationMembership(UUID.randomUUID(), org, user.getId(), role, clock.instant()));
        return user;
    }

    private long activityCount() {
        return jdbc.sql("SELECT count(*) FROM activity_log").query(Long.class).single();
    }

    private IdentitySnapshot snapshot(ExternalIdentity identity) {
        return new IdentitySnapshot(
                identity.getId(),
                identity.getUserId(),
                identity.getIssuer(),
                identity.getSubject(),
                identity.getLastEmail(),
                identity.getLastDisplayName(),
                identity.getCreatedAt(),
                identity.getLastLoginAt(),
                identity.getUnlinkedAt());
    }

    private record IdentitySnapshot(
            UUID id,
            UUID userId,
            String issuer,
            String subject,
            String email,
            String displayName,
            Instant createdAt,
            Instant lastLoginAt,
            Instant unlinkedAt) {}
}
