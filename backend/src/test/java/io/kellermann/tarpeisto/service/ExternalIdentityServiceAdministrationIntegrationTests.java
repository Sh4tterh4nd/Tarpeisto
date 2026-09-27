package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.Organization;
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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only external-identity administration: list/link/unlink, role enforcement, cross-
 * organization isolation, and the last-usable-Owner-authentication-method protection
 * (specification section 4.3/28, ADR-0003; implementation plan section 3.2/3.3). Exercised at the
 * service layer (real PostgreSQL, real repositories) with hand-built {@link TarpeistoPrincipal}
 * values rather than through HTTP, so a deliberately passwordless (OIDC-only) Owner fixture - the
 * scenario the last-usable-auth-method guard exists for - does not require a live OIDC provider
 * login to set up.
 */
class ExternalIdentityServiceAdministrationIntegrationTests extends AbstractIntegrationTest {

    @Autowired
    private ExternalIdentityService externalIdentityService;

    @Autowired
    private ExternalIdentityRepository identityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private Clock clock;

    @Test
    @Transactional
    void aNonOwnerCannotListLinkOrUnlink() {
        Organization organization =
                organizationService.ensureOrganizationExists("ExtId Admin Org " + UUID.randomUUID());
        User viewer = createUser(organization, "viewer@example.org", true, null, OrganizationRole.VIEWER);
        TarpeistoPrincipal viewerPrincipal = principalFor(viewer, organization, OrganizationRole.VIEWER);

        assertThatThrownBy(() -> externalIdentityService.listIdentities(viewerPrincipal, viewer.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> externalIdentityService.createLink(viewerPrincipal, viewer.getId(), "issuer", "sub"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> externalIdentityService.unlink(viewerPrincipal, UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @Transactional
    void ownerCanListLinkAndUnlink() {
        Organization organization =
                organizationService.ensureOrganizationExists("ExtId Admin Org " + UUID.randomUUID());
        User owner = createUser(organization, "owner@example.org", true, "hash", OrganizationRole.OWNER);
        User viewer = createUser(organization, "viewer2@example.org", true, "hash", OrganizationRole.VIEWER);
        TarpeistoPrincipal ownerPrincipal = principalFor(owner, organization, OrganizationRole.OWNER);

        assertThat(externalIdentityService.listIdentities(ownerPrincipal, viewer.getId()))
                .isEmpty();

        var created =
                externalIdentityService.createLink(ownerPrincipal, viewer.getId(), "https://idp.example.org", "sub-1");
        assertThat(created.active()).isTrue();
        assertThat(externalIdentityService.listIdentities(ownerPrincipal, viewer.getId()))
                .hasSize(1);

        externalIdentityService.unlink(ownerPrincipal, created.id());

        var afterUnlink = externalIdentityService.listIdentities(ownerPrincipal, viewer.getId());
        assertThat(afterUnlink).hasSize(1);
        assertThat(afterUnlink.get(0).active()).isFalse();
    }

    @Test
    @Transactional
    void aTargetUserFromAnotherOrganizationIsNotFound() {
        Organization organizationA =
                organizationService.ensureOrganizationExists("ExtId Admin Org A " + UUID.randomUUID());
        Organization organizationB =
                organizationService.ensureOrganizationExists("ExtId Admin Org B " + UUID.randomUUID());
        User ownerA = createUser(organizationA, "ownerA@example.org", true, "hash", OrganizationRole.OWNER);
        User viewerB = createUser(organizationB, "viewerB@example.org", true, "hash", OrganizationRole.VIEWER);
        TarpeistoPrincipal ownerAPrincipal = principalFor(ownerA, organizationA, OrganizationRole.OWNER);

        assertThatThrownBy(() -> externalIdentityService.listIdentities(ownerAPrincipal, viewerB.getId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> externalIdentityService.createLink(ownerAPrincipal, viewerB.getId(), "issuer", "sub"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @Transactional
    void unlinkingTheOnlyOwnersOnlyUsableAuthenticationMethodIsRejected() {
        Organization organization =
                organizationService.ensureOrganizationExists("ExtId Admin Org " + UUID.randomUUID());
        // A passwordless (OIDC-only) Owner: the schema explicitly supports this (V3 migration),
        // and this is exactly the scenario the last-usable-auth-method guard protects.
        User oidcOnlyOwner = createUser(organization, "oidc-owner@example.org", true, null, OrganizationRole.OWNER);
        TarpeistoPrincipal ownerPrincipal = principalFor(oidcOnlyOwner, organization, OrganizationRole.OWNER);
        var identity = externalIdentityService.createLink(
                ownerPrincipal, oidcOnlyOwner.getId(), "https://idp.example.org", "only-sub");

        assertThatThrownBy(() -> externalIdentityService.unlink(ownerPrincipal, identity.id()))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(identityRepository.findById(identity.id()).orElseThrow().isActive())
                .isTrue();
    }

    @Test
    @Transactional
    void unlinkingSucceedsWhenAnotherEnabledOwnerHasAUsableAuthenticationMethod() {
        Organization organization =
                organizationService.ensureOrganizationExists("ExtId Admin Org " + UUID.randomUUID());
        User oidcOnlyOwner = createUser(organization, "oidc-owner2@example.org", true, null, OrganizationRole.OWNER);
        // A second Owner with a local password: the installation retains a usable recovery
        // method even after the first Owner's only identity is unlinked.
        createUser(organization, "local-owner@example.org", true, "hash", OrganizationRole.OWNER);
        TarpeistoPrincipal ownerPrincipal = principalFor(oidcOnlyOwner, organization, OrganizationRole.OWNER);
        var identity = externalIdentityService.createLink(
                ownerPrincipal, oidcOnlyOwner.getId(), "https://idp.example.org", "only-sub-2");

        externalIdentityService.unlink(ownerPrincipal, identity.id());

        assertThat(identityRepository.findById(identity.id()).orElseThrow().isActive())
                .isFalse();
    }

    private TarpeistoPrincipal principalFor(User user, Organization organization, OrganizationRole role) {
        return new TarpeistoPrincipal(
                user.getId(), user.getUsername(), user.getDisplayName(), organization.getId(), role);
    }

    private User createUser(
            Organization organization,
            String email,
            boolean enabled,
            String passwordHashOrNull,
            OrganizationRole role) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(), "user-" + UUID.randomUUID(), email, "Test User", passwordHashOrNull, enabled, now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }
}
