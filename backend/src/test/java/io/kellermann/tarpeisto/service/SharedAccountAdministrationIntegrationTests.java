package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.ArchiveConflictException;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.model.ExternalIdentity;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ExternalIdentityRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

class SharedAccountAdministrationIntegrationTests extends AbstractIntegrationTest {
    @Autowired
    private OrganizationService organizations;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrganizationMembershipRepository memberships;

    @Autowired
    private ExternalIdentityRepository identities;

    @Autowired
    private ExternalIdentityService links;

    @Autowired
    private UserService administration;

    @Autowired
    private Clock clock;

    @Autowired
    private org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Test
    @Transactional
    void sharedOwnerGlobalAccessAndSignInMethodsCannotBeChangedFromEitherOrganization() {
        var caller = owner();
        var other = owner();
        var shared = users.save(new User(
                UUID.randomUUID(), "shared-" + UUID.randomUUID(), null, "Shared Owner", "hash", true, clock.instant()));
        memberships.save(new OrganizationMembership(
                UUID.randomUUID(), caller.organizationId(), shared.getId(), OrganizationRole.VIEWER, clock.instant()));
        var remoteMembership = memberships.save(new OrganizationMembership(
                UUID.randomUUID(),
                other.organizationId(),
                shared.getId(),
                OrganizationRole.OWNER,
                clock.instant().minusSeconds(60)));
        var identity = identities.save(new ExternalIdentity(
                UUID.randomUUID(),
                shared.getId(),
                "https://existing.example",
                UUID.randomUUID().toString(),
                null,
                "Shared",
                clock.instant()));
        entityManager.flush();
        String targetSessionId = indexedSession(sessions, shared.getUsername());
        assertThat(sessions.findByPrincipalName(shared.getUsername())).containsKey(targetSessionId);
        long activity = count("activity_log");
        long sessionCount = count("spring_session");
        assertThatThrownBy(() -> administration.setEnabled(caller, shared.getId(), false))
                .isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> administration.setEnabled(caller, shared.getId(), true))
                .isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> links.listIdentities(caller, shared.getId()))
                .isInstanceOf(ArchiveConflictException.class);
        String controlledSubject = UUID.randomUUID().toString();
        assertThatThrownBy(
                        () -> links.createLink(caller, shared.getId(), "https://attacker.example", controlledSubject))
                .isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> links.unlink(caller, shared.getId(), identity.getId()))
                .isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> links.unlink(caller, identity.getId())).isInstanceOf(ArchiveConflictException.class);
        assertThatThrownBy(() -> administration.setEnabled(other, shared.getId(), false))
                .isInstanceOf(ArchiveConflictException.class);
        assertThat(identities.findByIssuerAndSubject("https://attacker.example", controlledSubject))
                .isEmpty();
        entityManager.flush();
        entityManager.clear();
        assertThat(users.findById(shared.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(identities.findById(identity.getId()).orElseThrow().isActive())
                .isTrue();
        assertThat(memberships.findById(remoteMembership.getId()).orElseThrow().getRole())
                .isEqualTo(OrganizationRole.OWNER);
        assertThat(count("activity_log")).isEqualTo(activity);
        assertThat(count("spring_session")).isEqualTo(sessionCount);
        assertThat(sessions.findByPrincipalName(shared.getUsername())).containsKey(targetSessionId);
        var login = links.resolveLogin(identity.getIssuer(), identity.getSubject(), null, false, "Shared");
        assertThat(login).isInstanceOf(ExternalIdentityService.OidcLoginOutcome.Authenticated.class);
        var principal = ((ExternalIdentityService.OidcLoginOutcome.Authenticated) login).principal();
        assertThat(principal.organizationId()).isEqualTo(other.organizationId());
        assertThat(principal.role()).isEqualTo(OrganizationRole.OWNER);
    }

    @Test
    @Transactional
    void missingForeignAndMismatchedNestedIdentityAreIndistinguishableAndHaveNoSideEffect() {
        var caller = owner();
        var other = owner();
        var identity = identities.save(new ExternalIdentity(
                UUID.randomUUID(),
                caller.userId(),
                "https://nested.example",
                UUID.randomUUID().toString(),
                null,
                "Owner",
                clock.instant()));
        var foreign = identities.save(new ExternalIdentity(
                UUID.randomUUID(),
                other.userId(),
                "https://foreign.example",
                UUID.randomUUID().toString(),
                null,
                "Other",
                clock.instant()));
        assertThatThrownBy(() -> administration.setEnabled(caller, other.userId(), false))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found.");
        assertThatThrownBy(() -> links.createLink(caller, other.userId(), "https://x.example", "sub"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found.");
        assertThatThrownBy(() -> links.unlink(caller, caller.userId(), foreign.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("External identity not found.");
        assertThatThrownBy(() -> links.unlink(caller, other.userId(), identity.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("External identity not found.");
        assertThatThrownBy(() -> links.unlink(caller, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("External identity not found.");
        assertThatThrownBy(() -> links.unlink(caller, foreign.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("External identity not found.");
        assertThat(identity.isActive()).isTrue();
        assertThat(foreign.isActive()).isTrue();
    }

    private <S extends org.springframework.session.Session> String indexedSession(
            org.springframework.session.FindByIndexNameSessionRepository<S> repository, String username) {
        S session = repository.createSession();
        session.setAttribute(
                org.springframework.session.FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, username);
        repository.save(session);
        return session.getId();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private TarpeistoPrincipal owner() {
        var org = organizations.ensureOrganizationExists("Shared safety " + UUID.randomUUID());
        var user = users.save(new User(
                UUID.randomUUID(), "owner-" + UUID.randomUUID(), null, "Owner", "hash", true, clock.instant()));
        memberships.save(new OrganizationMembership(
                UUID.randomUUID(), org.getId(), user.getId(), OrganizationRole.OWNER, clock.instant()));
        return new TarpeistoPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), org.getId(), OrganizationRole.OWNER);
    }
}
