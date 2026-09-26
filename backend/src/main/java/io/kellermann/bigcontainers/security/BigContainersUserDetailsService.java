package io.kellermann.bigcontainers.security;

import io.kellermann.bigcontainers.model.OrganizationMembership;
import io.kellermann.bigcontainers.model.User;
import io.kellermann.bigcontainers.repository.OrganizationMembershipRepository;
import io.kellermann.bigcontainers.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the local-login principal by username for {@code DaoAuthenticationProvider}.
 *
 * <p>Every failure path here throws the same {@link UsernameNotFoundException}, which {@code
 * DaoAuthenticationProvider} (configured with {@code hideUserNotFoundExceptions = true} in {@link
 * AuthenticationBeansConfiguration}) converts into the same {@code BadCredentialsException} as an
 * incorrect password. That, combined with the provider's built-in constant-time-ish dummy
 * password comparison for a missing user, is what makes an unknown username and a wrong password
 * indistinguishable in {@link SessionAuthenticationService}: both surface as one generic {@link
 * io.kellermann.bigcontainers.exception.InvalidCredentialsException}.
 */
@Component
class BigContainersUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final OrganizationMembershipRepository membershipRepository;

    BigContainersUserDetailsService(
            UserRepository userRepository, OrganizationMembershipRepository membershipRepository) {
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository
                .findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new UsernameNotFoundException("No local account for the supplied username."));
        if (user.getPasswordHash() == null) {
            // No local credential (reserved for a future OIDC-only user): local login is
            // unavailable, but this must not read differently from "unknown user" to the caller.
            throw new UsernameNotFoundException("No local credential for this user.");
        }
        OrganizationMembership membership = membershipRepository
                .findFirstByUserIdOrderByCreatedAtAsc(user.getId())
                .orElseThrow(() -> new UsernameNotFoundException("User has no organization membership."));
        return new BigContainersUserDetails(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                user.getDisplayName(),
                membership.getOrganizationId(),
                membership.getRole(),
                user.isEnabled());
    }
}
