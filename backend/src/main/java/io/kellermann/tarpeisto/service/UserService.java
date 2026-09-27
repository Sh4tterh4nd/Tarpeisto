package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.exception.ValidationFailedException;
import io.kellermann.tarpeisto.model.OrganizationMembership;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.User;
import io.kellermann.tarpeisto.repository.OrganizationMembershipRepository;
import io.kellermann.tarpeisto.repository.UserRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner-only permanent-account administration: list, create, change role, enable/disable
 * (implementation plan section 3.2). Every method here re-checks the caller's role itself - the
 * server-side authorization decision lives here, in the service layer, not only in {@code
 * SecurityConfiguration}'s URL matchers and not as a controller-side shortcut, per
 * docs/DEVELOPMENT_POLICIES.md section 5.1.
 *
 * <p>Every query and write is scoped to {@code principal.organizationId()}, which is the
 * server-side active-organization context resolved at login - never a client-supplied value (see
 * {@link TarpeistoPrincipal}). A target user that exists but has no membership in the caller's
 * organization is reported as {@link NotFoundException}, identical to a target user that does not
 * exist at all, per the 404-vs-403 policy.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final ActivityLogService activityLogService;
    private final Clock clock;
    private final FindByIndexNameSessionRepository<?> sessionRepository;

    public UserService(
            UserRepository userRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            ActivityLogService activityLogService,
            Clock clock,
            FindByIndexNameSessionRepository<?> sessionRepository) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.activityLogService = activityLogService;
        this.clock = clock;
        this.sessionRepository = sessionRepository;
    }

    @Transactional(readOnly = true)
    public List<UserSummaryView> listUsers(TarpeistoPrincipal principal) {
        requireOwner(principal);
        List<OrganizationMembership> memberships =
                membershipRepository.findAllByOrganizationIdOrderByCreatedAtAsc(principal.organizationId());
        List<UUID> userIds =
                memberships.stream().map(OrganizationMembership::getUserId).toList();
        Map<UUID, User> usersById = userRepository.findAllById(userIds).stream()
                .collect(java.util.stream.Collectors.toMap(User::getId, u -> u));
        return memberships.stream()
                .map(membership -> toView(usersById.get(membership.getUserId()), membership))
                .toList();
    }

    @Transactional
    public UserSummaryView createUser(
            TarpeistoPrincipal principal,
            String username,
            String rawPassword,
            String displayName,
            String email,
            OrganizationRole role) {
        requireOwner(principal);
        if (userRepository.findByUsernameIgnoreCase(username).isPresent()) {
            throw new ValidationFailedException("Username is already taken.");
        }

        var now = clock.instant();
        User user = new User(
                UUID.randomUUID(), username, email, displayName, passwordEncoder.encode(rawPassword), true, now);
        userRepository.save(user);
        OrganizationMembership membership =
                new OrganizationMembership(UUID.randomUUID(), principal.organizationId(), user.getId(), role, now);
        membershipRepository.save(membership);

        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "USER_CREATED",
                "USER",
                user.getId(),
                Map.of("username", username, "role", role.name()));
        return toView(user, membership);
    }

    @Transactional
    public void changeRole(TarpeistoPrincipal principal, UUID targetUserId, OrganizationRole newRole) {
        requireOwner(principal);
        OrganizationMembership membership = requireMembership(principal.organizationId(), targetUserId);

        OrganizationRole previousRole = membership.getRole();
        if (previousRole == OrganizationRole.OWNER && newRole != OrganizationRole.OWNER) {
            requireAnotherEnabledOwnerRemains(principal.organizationId(), targetUserId);
        }

        membership.changeRole(newRole, clock.instant());
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                "USER_ROLE_CHANGED",
                "USER",
                targetUserId,
                Map.of("from", previousRole.name(), "to", newRole.name()));
    }

    @Transactional
    public void setEnabled(TarpeistoPrincipal principal, UUID targetUserId, boolean enabled) {
        requireOwner(principal);
        OrganizationMembership membership = requireMembership(principal.organizationId(), targetUserId);
        User user = userRepository.findById(targetUserId).orElseThrow(UserService::userNotFound);

        if (!enabled && membership.getRole() == OrganizationRole.OWNER) {
            requireAnotherEnabledOwnerRemains(principal.organizationId(), targetUserId);
        }

        var now = clock.instant();
        if (enabled) {
            user.enable(now);
        } else {
            user.disable(now);
            // A disabled user must be denied for the remainder of any session they already
            // hold, not only on their next login attempt: expire every active session indexed
            // under their username (Spring Session JDBC's PRINCIPAL_NAME index, populated
            // because TarpeistoPrincipal implements AuthenticatedPrincipal).
            sessionRepository.findByPrincipalName(user.getUsername()).keySet().forEach(sessionRepository::deleteById);
        }
        activityLogService.record(
                principal.organizationId(),
                principal.userId(),
                enabled ? "USER_ENABLED" : "USER_DISABLED",
                "USER",
                targetUserId,
                null);
    }

    /**
     * Rejects removing or disabling the last usable (enabled) Owner of an organization
     * (specification section 4.3/28, ADR-0003), regardless of whether the caller is trying to
     * demote or disable that Owner.
     */
    private void requireAnotherEnabledOwnerRemains(UUID organizationId, UUID excludingUserId) {
        List<UUID> otherOwnerUserIds =
                membershipRepository.findAllByOrganizationIdAndRole(organizationId, OrganizationRole.OWNER).stream()
                        .map(OrganizationMembership::getUserId)
                        .filter(userId -> !userId.equals(excludingUserId))
                        .toList();
        boolean anotherEnabledOwnerExists = !otherOwnerUserIds.isEmpty()
                && userRepository.findAllById(otherOwnerUserIds).stream().anyMatch(User::isEnabled);
        if (!anotherEnabledOwnerExists) {
            throw new ValidationFailedException("At least one enabled Owner must remain in the organization.");
        }
    }

    private OrganizationMembership requireMembership(UUID organizationId, UUID userId) {
        return membershipRepository
                .findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(UserService::userNotFound);
    }

    private static NotFoundException userNotFound() {
        // Identical whether the user id does not exist at all or belongs to another
        // organization, per the 404-vs-403 policy in NotFoundException's contract.
        return new NotFoundException("User not found.");
    }

    private void requireOwner(TarpeistoPrincipal principal) {
        if (principal == null || principal.role() != OrganizationRole.OWNER) {
            throw new AccessDeniedException("Owner role required.");
        }
    }

    private static UserSummaryView toView(User user, OrganizationMembership membership) {
        return new UserSummaryView(
                user.getId(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.isEnabled(),
                membership.getRole(),
                user.getCreatedAt());
    }
}
