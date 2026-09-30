package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.config.AuthenticationProperties;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.ExternalIdentity;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.ExternalIdentityRepository;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-facing external-identity administration (list/link/unlink) and the OIDC login-time
 * identity resolution/auto-linking decision (ADR-0003, specification section 4.3/28,
 * implementation plan section 3.2/3.3).
 *
 * <p>{@link #resolveLogin} is called once per successful OIDC provider authentication, from {@code
 * io.kellermann.tarpeisto.security.TarpeistoOidcUserService}, <strong>before</strong> any
 * Tarpeisto session exists. It never creates a {@link User}: just-in-time provisioning is out
 * of scope (ADR-0003), so a claim set that cannot be resolved to exactly one existing, eligible
 * internal account is rejected with a specific, actionable {@link OidcDenialReason} rather than
 * guessing.
 */
@Service
public class ExternalIdentityService {

    private final ExternalIdentityRepository identityRepository;
    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final ActivityLogService activityLogService;
    private final AuthenticationProperties authenticationProperties;
    private final Clock clock;

    public ExternalIdentityService(
            ExternalIdentityRepository identityRepository,
            UserRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            ActivityLogService activityLogService,
            AuthenticationProperties authenticationProperties,
            Clock clock) {
        this.identityRepository = identityRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.activityLogService = activityLogService;
        this.authenticationProperties = authenticationProperties;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------------------
    // OIDC login-time resolution
    // -------------------------------------------------------------------------------------

    public sealed interface OidcLoginOutcome {
        record Authenticated(TarpeistoPrincipal principal) implements OidcLoginOutcome {}

        record Denied(OidcDenialReason reason) implements OidcLoginOutcome {}
    }

    public enum OidcDenialReason {
        /** A returning user's linked internal account is disabled. */
        ACCOUNT_DISABLED,
        /** The linked/matched internal account has no organization membership (defensive; should not occur). */
        NO_ORGANIZATION_MEMBERSHIP,
        /** No active link exists for this (issuer, subject) and automatic email linking is not enabled. */
        AUTO_LINKING_NOT_APPLICABLE,
        EMAIL_MISSING,
        EMAIL_NOT_VERIFIED,
        NO_MATCHING_ACCOUNT,
        AMBIGUOUS_EMAIL_MATCH
    }

    /**
     * Resolves one successful OIDC provider authentication to either an existing internal user (a
     * returning login, or a freshly auto-linked one) or a specific denial reason.
     *
     * <p>{@code (issuer, subject)} is the only durable lookup key (ADR-0003): a previously linked
     * identity is recognized and its profile attributes refreshed here regardless of what {@code
     * email}/{@code displayName} the provider reports this time - a later email change never
     * changes which internal user this login resolves to.
     */
    @Transactional
    public OidcLoginOutcome resolveLogin(
            String issuer, String subject, String email, boolean emailVerified, String displayName) {
        Instant now = clock.instant();
        Optional<ExternalIdentity> existing = identityRepository.findByIssuerAndSubject(issuer, subject);
        if (existing.isPresent() && existing.get().isActive()) {
            return authenticateLinkedIdentity(existing.get(), email, displayName, now);
        }

        if (!authenticationProperties.autoLinkByEmail()) {
            return new OidcLoginOutcome.Denied(OidcDenialReason.AUTO_LINKING_NOT_APPLICABLE);
        }

        String normalizedEmail = normalizeEmail(email);
        List<AutomaticEmailLinkingPolicy.CandidateAccount> candidates = normalizedEmail == null
                ? List.of()
                : userRepository.findAllByEmailIgnoreCase(normalizedEmail).stream()
                        .map(user -> new AutomaticEmailLinkingPolicy.CandidateAccount(user.getId(), user.isEnabled()))
                        .toList();
        AutomaticEmailLinkingPolicy.Decision decision =
                AutomaticEmailLinkingPolicy.decide(emailVerified, normalizedEmail, candidates);
        return switch (decision) {
            case AutomaticEmailLinkingPolicy.Decision.Reject reject ->
                new OidcLoginOutcome.Denied(toDenialReason(reject.reason()));
            case AutomaticEmailLinkingPolicy.Decision.Link link ->
                autoLink(link.userId(), issuer, subject, email, displayName, existing, now);
        };
    }

    private OidcLoginOutcome authenticateLinkedIdentity(
            ExternalIdentity identity, String email, String displayName, Instant now) {
        User user = userRepository.findById(identity.getUserId()).orElseThrow(ExternalIdentityService::userNotFound);
        if (!user.isEnabled()) {
            return new OidcLoginOutcome.Denied(OidcDenialReason.ACCOUNT_DISABLED);
        }
        identity.recordLogin(email, displayName, now);
        return buildAuthenticatedOutcome(user, "SESSION_LOGIN_SUCCEEDED", null);
    }

    private OidcLoginOutcome autoLink(
            UUID userId,
            String issuer,
            String subject,
            String email,
            String displayName,
            Optional<ExternalIdentity> existingInactive,
            Instant now) {
        User user = userRepository.findById(userId).orElseThrow(ExternalIdentityService::userNotFound);
        ExternalIdentity identity;
        if (existingInactive.isPresent()) {
            identity = existingInactive.get();
            identity.relink(userId, now);
            identity.recordLogin(email, displayName, now);
        } else {
            identity = new ExternalIdentity(UUID.randomUUID(), userId, issuer, subject, email, displayName, now);
            identityRepository.save(identity);
        }
        return buildAuthenticatedOutcome(user, "EXTERNAL_IDENTITY_AUTO_LINKED", identity.getId());
    }

    private OidcLoginOutcome buildAuthenticatedOutcome(User user, String linkActivityAction, UUID identityIdOrNull) {
        Optional<OrganizationMembership> membership =
                membershipRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId());
        if (membership.isEmpty()) {
            return new OidcLoginOutcome.Denied(OidcDenialReason.NO_ORGANIZATION_MEMBERSHIP);
        }
        TarpeistoPrincipal principal = new TarpeistoPrincipal(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                membership.get().getOrganizationId(),
                membership.get().getRole());
        if (identityIdOrNull != null) {
            activityLogService.record(
                    principal.organizationId(),
                    principal.userId(),
                    linkActivityAction,
                    "EXTERNAL_IDENTITY",
                    identityIdOrNull,
                    null);
        }
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "SESSION_LOGIN_SUCCEEDED",
                "USER",
                principal.userId(),
                Map.of("method", "OIDC"));
        return new OidcLoginOutcome.Authenticated(principal);
    }

    private static OidcDenialReason toDenialReason(AutomaticEmailLinkingPolicy.Reason reason) {
        return switch (reason) {
            case EMAIL_MISSING -> OidcDenialReason.EMAIL_MISSING;
            case EMAIL_NOT_VERIFIED -> OidcDenialReason.EMAIL_NOT_VERIFIED;
            case NO_MATCHING_ACCOUNT -> OidcDenialReason.NO_MATCHING_ACCOUNT;
            case AMBIGUOUS_EMAIL_MATCH -> OidcDenialReason.AMBIGUOUS_EMAIL_MATCH;
            case ACCOUNT_DISABLED -> OidcDenialReason.ACCOUNT_DISABLED;
        };
    }

    private static String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    // -------------------------------------------------------------------------------------
    // Owner-facing administration
    // -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ExternalIdentityView> listIdentities(TarpeistoPrincipal principal, UUID targetUserId) {
        if (principal != null) principal.requirePermanent();
        requireOwner(principal);
        requireMembership(principal.organizationId(), targetUserId);
        return identityRepository.findAllByUserIdOrderByCreatedAtAsc(targetUserId).stream()
                .map(ExternalIdentityService::toView)
                .toList();
    }

    /** Owner-approved or preconfigured linking (ADR-0003's safe default), independent of email matching. */
    @Transactional
    public ExternalIdentityView createLink(
            TarpeistoPrincipal principal, UUID targetUserId, String issuer, String subject) {
        if (principal != null) principal.requirePermanent();
        requireOwner(principal);
        requireMembership(principal.organizationId(), targetUserId);

        Instant now = clock.instant();
        Optional<ExternalIdentity> existing = identityRepository.findByIssuerAndSubject(issuer, subject);
        ExternalIdentity identity;
        if (existing.isPresent() && existing.get().isActive()) {
            throw new ValidationFailedException(
                    existing.get().getUserId().equals(targetUserId)
                            ? "This external identity is already linked to this user."
                            : "This external identity is already linked to a different user.");
        } else if (existing.isPresent()) {
            identity = existing.get();
            identity.relink(targetUserId, now);
        } else {
            identity = new ExternalIdentity(UUID.randomUUID(), targetUserId, issuer, subject, null, null, now);
            identityRepository.save(identity);
        }

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "EXTERNAL_IDENTITY_LINKED",
                "EXTERNAL_IDENTITY",
                identity.getId(),
                Map.of("targetUserId", targetUserId.toString()));
        return toView(identity);
    }

    /**
     * Unlinks an external identity (Owner action), rejecting it if doing so would leave an
     * enabled Owner with no remaining usable authentication method anywhere in the organization
     * (specification section 4.3/28, ADR-0003: "The system prevents removal of the last usable
     * Owner authentication method" - the same protection {@code UserService} already applies to
     * Owner *membership*, extended here to authentication *methods*).
     */
    @Transactional
    public void unlink(TarpeistoPrincipal principal, UUID externalIdentityId) {
        if (principal != null) principal.requirePermanent();
        requireOwner(principal);
        ExternalIdentity identity =
                identityRepository.findById(externalIdentityId).orElseThrow(ExternalIdentityService::identityNotFound);
        OrganizationMembership targetMembership = requireMembership(principal.organizationId(), identity.getUserId());
        User targetUser =
                userRepository.findById(identity.getUserId()).orElseThrow(ExternalIdentityService::userNotFound);

        if (!identity.isActive()) {
            throw new ValidationFailedException("This external identity is already unlinked.");
        }
        requireAnotherUsableOwnerAuthMethodRemains(targetMembership, targetUser, identity);

        identity.unlink(clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "EXTERNAL_IDENTITY_UNLINKED",
                "EXTERNAL_IDENTITY",
                identity.getId(),
                null);
    }

    private void requireAnotherUsableOwnerAuthMethodRemains(
            OrganizationMembership targetMembership, User targetUser, ExternalIdentity identityBeingUnlinked) {
        if (targetMembership.getRole() != OrganizationRole.OWNER || !targetUser.isEnabled()) {
            // Only an enabled Owner's authentication methods are protected: a Deputy/Operator/
            // Viewer or an already-disabled Owner has no "last usable Owner sign-in" to protect.
            return;
        }
        if (hasUsableAuthMethod(targetUser, identityBeingUnlinked.getId())) {
            return;
        }
        boolean anotherUsableOwnerExists =
                membershipRepository
                        .findAllByOrganizationIdAndRole(targetMembership.getOrganizationId(), OrganizationRole.OWNER)
                        .stream()
                        .filter(membership -> !membership.getUserId().equals(targetUser.getId()))
                        .map(membership -> userRepository.findById(membership.getUserId()))
                        .flatMap(Optional::stream)
                        .anyMatch(owner -> owner.isEnabled() && hasUsableAuthMethod(owner, null));
        if (!anotherUsableOwnerExists) {
            throw new ValidationFailedException(
                    "At least one enabled Owner must retain a usable authentication method (a local password or a"
                            + " linked external identity).");
        }
    }

    /** Whether {@code user} has a usable sign-in method other than {@code excludingIdentityId}. */
    private boolean hasUsableAuthMethod(User user, UUID excludingIdentityId) {
        if (user.getPasswordHash() != null) {
            return true;
        }
        return identityRepository.findAllByUserIdOrderByCreatedAtAsc(user.getId()).stream()
                .anyMatch(identity -> identity.isActive() && !identity.getId().equals(excludingIdentityId));
    }

    private OrganizationMembership requireMembership(UUID organizationId, UUID userId) {
        return membershipRepository
                .findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(ExternalIdentityService::userNotFound);
    }

    private void requireOwner(TarpeistoPrincipal principal) {
        if (principal == null || principal.role() != OrganizationRole.OWNER) {
            throw new AccessDeniedException("Owner role required.");
        }
    }

    private static NotFoundException userNotFound() {
        return new NotFoundException("User not found.");
    }

    private static NotFoundException identityNotFound() {
        return new NotFoundException("External identity not found.");
    }

    private static ExternalIdentityView toView(ExternalIdentity identity) {
        return new ExternalIdentityView(
                identity.getId(),
                identity.getIssuer(),
                identity.getSubject(),
                identity.getLastEmail(),
                identity.getLastDisplayName(),
                identity.getCreatedAt(),
                identity.getLastLoginAt(),
                identity.isActive());
    }
}
