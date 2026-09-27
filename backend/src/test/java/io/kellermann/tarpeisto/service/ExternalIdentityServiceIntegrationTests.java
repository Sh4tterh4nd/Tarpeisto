package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.AbstractIntegrationTest;
import io.kellermann.tarpeisto.config.AuthenticationMode;
import io.kellermann.tarpeisto.config.AuthenticationProperties;
import io.kellermann.tarpeisto.model.ExternalIdentity;
import io.kellermann.tarpeisto.model.Organization;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ExternalIdentityRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.service.ExternalIdentityService.OidcDenialReason;
import io.kellermann.tarpeisto.service.ExternalIdentityService.OidcLoginOutcome;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Covers implementation plan section 3.3's OIDC login-resolution requirements against a real
 * PostgreSQL database: {@code (issuer, subject)} permanence across an email change, the automatic
 * email-linking wiring (success and every rejection reaching real repositories, not just the pure
 * {@link AutomaticEmailLinkingPolicy}), and disabled-account denial for both a fresh auto-link
 * candidate and an already-linked returning user.
 *
 * <p>Auto-linking is disabled by default (ADR-0003); tests that need it enabled construct their
 * own {@link ExternalIdentityService} instance around the same autowired, real repositories rather
 * than standing up a second Spring context, keeping this test class in the default (fast, shared)
 * integration test context.
 */
class ExternalIdentityServiceIntegrationTests extends AbstractIntegrationTest {

    private static final String ISSUER = "https://idp.example.org";

    @Autowired
    private ExternalIdentityService externalIdentityService; // auto-link disabled (default)

    @Autowired
    private ExternalIdentityRepository identityRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private ActivityLogService activityLogService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Clock clock;

    private ExternalIdentityService autoLinkEnabledService() {
        return new ExternalIdentityService(
                identityRepository,
                userRepository,
                membershipRepository,
                activityLogService,
                new AuthenticationProperties(AuthenticationMode.LOCAL_ONLY, null, true),
                clock);
    }

    @Test
    @Transactional
    void withAutoLinkingDisabledAnUnknownIdentityIsDenied() {
        String subject = "subject-" + UUID.randomUUID();

        OidcLoginOutcome outcome =
                externalIdentityService.resolveLogin(ISSUER, subject, "someone@example.org", true, "Someone");

        assertThat(outcome).isInstanceOf(OidcLoginOutcome.Denied.class);
        assertThat(((OidcLoginOutcome.Denied) outcome).reason())
                .isEqualTo(OidcDenialReason.AUTO_LINKING_NOT_APPLICABLE);
        assertThat(identityRepository.findByIssuerAndSubject(ISSUER, subject)).isEmpty();
    }

    @Test
    @Transactional
    void aVerifiedUniqueEmailMatchAutoLinksAndLaterEmailChangesDoNotAlterTheLink() {
        Organization organization = organizationService.ensureOrganizationExists("ExtId Org " + UUID.randomUUID());
        User user = createUser(organization, "match@example.org", true, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();
        ExternalIdentityService service = autoLinkEnabledService();

        OidcLoginOutcome first = service.resolveLogin(ISSUER, subject, "match@example.org", true, "First Name");
        assertThat(first).isInstanceOf(OidcLoginOutcome.Authenticated.class);
        assertThat(((OidcLoginOutcome.Authenticated) first).principal().userId())
                .isEqualTo(user.getId());

        ExternalIdentity linked =
                identityRepository.findByIssuerAndSubject(ISSUER, subject).orElseThrow();
        UUID linkedRowId = linked.getId();
        assertThat(linked.isActive()).isTrue();
        assertThat(linked.getLastEmail()).isEqualTo("match@example.org");

        // A later login reports a DIFFERENT email for the SAME (issuer, subject). The link must
        // not move to a different user, and no second row is created for this issuer/subject.
        OidcLoginOutcome second = service.resolveLogin(ISSUER, subject, "changed@example.org", true, "New Name");
        assertThat(second).isInstanceOf(OidcLoginOutcome.Authenticated.class);
        assertThat(((OidcLoginOutcome.Authenticated) second).principal().userId())
                .isEqualTo(user.getId());

        List<ExternalIdentity> rowsForUser = identityRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId());
        assertThat(rowsForUser).hasSize(1);
        assertThat(rowsForUser.get(0).getId()).isEqualTo(linkedRowId);
        assertThat(rowsForUser.get(0).getLastEmail()).isEqualTo("changed@example.org");
    }

    @Test
    @Transactional
    void anAmbiguousEmailMatchIsDeniedAndCreatesNoLink() {
        Organization organization = organizationService.ensureOrganizationExists("ExtId Org " + UUID.randomUUID());
        createUser(organization, "ambiguous@example.org", true, OrganizationRole.VIEWER);
        createUser(organization, "ambiguous@example.org", true, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();

        OidcLoginOutcome outcome =
                autoLinkEnabledService().resolveLogin(ISSUER, subject, "ambiguous@example.org", true, "Ambiguous");

        assertThat(((OidcLoginOutcome.Denied) outcome).reason()).isEqualTo(OidcDenialReason.AMBIGUOUS_EMAIL_MATCH);
        assertThat(identityRepository.findByIssuerAndSubject(ISSUER, subject)).isEmpty();
    }

    @Test
    @Transactional
    void aDisabledCandidateAccountIsDeniedAndCreatesNoLink() {
        Organization organization = organizationService.ensureOrganizationExists("ExtId Org " + UUID.randomUUID());
        createUser(organization, "disabled@example.org", false, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();

        OidcLoginOutcome outcome =
                autoLinkEnabledService().resolveLogin(ISSUER, subject, "disabled@example.org", true, "Disabled");

        assertThat(((OidcLoginOutcome.Denied) outcome).reason()).isEqualTo(OidcDenialReason.ACCOUNT_DISABLED);
        assertThat(identityRepository.findByIssuerAndSubject(ISSUER, subject)).isEmpty();
    }

    @Test
    @Transactional
    void anUnverifiedEmailIsDeniedEvenWithAUniqueMatch() {
        Organization organization = organizationService.ensureOrganizationExists("ExtId Org " + UUID.randomUUID());
        createUser(organization, "unverified@example.org", true, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();

        OidcLoginOutcome outcome =
                autoLinkEnabledService().resolveLogin(ISSUER, subject, "unverified@example.org", false, "Unverified");

        assertThat(((OidcLoginOutcome.Denied) outcome).reason()).isEqualTo(OidcDenialReason.EMAIL_NOT_VERIFIED);
    }

    @Test
    @Transactional
    void aMissingEmailClaimIsDenied() {
        String subject = "subject-" + UUID.randomUUID();

        OidcLoginOutcome outcome = autoLinkEnabledService().resolveLogin(ISSUER, subject, null, true, "No Email");

        assertThat(((OidcLoginOutcome.Denied) outcome).reason()).isEqualTo(OidcDenialReason.EMAIL_MISSING);
    }

    @Test
    @Transactional
    void aDisabledUserRemainsDeniedAfterSuccessfulProviderAuthenticationOnAReturningLogin() {
        Organization organization = organizationService.ensureOrganizationExists("ExtId Org " + UUID.randomUUID());
        User user = createUser(organization, "returning@example.org", true, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();
        ExternalIdentityService service = autoLinkEnabledService();
        OidcLoginOutcome firstLogin = service.resolveLogin(ISSUER, subject, "returning@example.org", true, "Returning");
        assertThat(firstLogin).isInstanceOf(OidcLoginOutcome.Authenticated.class);

        user.disable(clock.instant());
        userRepository.saveAndFlush(user);

        OidcLoginOutcome secondLogin =
                service.resolveLogin(ISSUER, subject, "returning@example.org", true, "Returning");

        assertThat(secondLogin).isInstanceOf(OidcLoginOutcome.Denied.class);
        assertThat(((OidcLoginOutcome.Denied) secondLogin).reason()).isEqualTo(OidcDenialReason.ACCOUNT_DISABLED);
    }

    @Test
    @Transactional
    void anAlreadyActivelyLinkedIdentityReturnsToTheSameUserRegardlessOfLaterAmbiguousEmailMatches() {
        Organization organization = organizationService.ensureOrganizationExists("ExtId Org " + UUID.randomUUID());
        User linkedUser = createUser(organization, "already-linked@example.org", true, OrganizationRole.VIEWER);
        String subject = "subject-" + UUID.randomUUID();
        ExternalIdentityService service = autoLinkEnabledService();
        OidcLoginOutcome firstLogin =
                service.resolveLogin(ISSUER, subject, "already-linked@example.org", true, "First");
        assertThat(firstLogin).isInstanceOf(OidcLoginOutcome.Authenticated.class);

        // A second internal user now also shares this email, which would make a fresh
        // auto-link decision ambiguous - but this (issuer, subject) is already linked, so it is
        // never re-evaluated for linking at all; it must keep resolving to linkedUser.
        createUser(organization, "already-linked@example.org", true, OrganizationRole.VIEWER);

        OidcLoginOutcome secondLogin =
                service.resolveLogin(ISSUER, subject, "already-linked@example.org", true, "First");

        assertThat(secondLogin).isInstanceOf(OidcLoginOutcome.Authenticated.class);
        assertThat(((OidcLoginOutcome.Authenticated) secondLogin).principal().userId())
                .isEqualTo(linkedUser.getId());
    }

    private User createUser(Organization organization, String email, boolean enabled, OrganizationRole role) {
        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                "user-" + UUID.randomUUID(),
                email,
                "Test User",
                passwordEncoder.encode("CorrectHorseBattery1"),
                enabled,
                now);
        userRepository.save(user);
        membershipRepository.save(
                new OrganizationMembership(UUID.randomUUID(), organization.getId(), user.getId(), role, now));
        return user;
    }
}
