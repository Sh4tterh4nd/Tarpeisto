package io.kellermann.tarpeisto.security;

import io.kellermann.tarpeisto.model.OrganizationRole;
import java.io.Serializable;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * The clean, immutable principal placed in the {@code SecurityContext} (and therefore persisted
 * into the Spring Session JDBC-backed session) after a successful login.
 *
 * <p>Deliberately carries no password hash: {@link SessionAuthenticationService} discards the
 * password-bearing {@link TarpeistoUserDetails} returned by {@code
 * DaoAuthenticationProvider} immediately after authentication succeeds and never lets it reach
 * the session, per the Phase 1 rule that passwords/hashes are never logged, returned, or placed
 * anywhere beyond the authentication check itself.
 *
 * <p>{@link #organizationId()} is the server-side active-organization context (specification
 * section 3.1/28): it is resolved once, at login, from the authenticated user's earliest {@code
 * organization_membership} row - never from any client-supplied value - and every subsequent
 * request in this session reuses this same value via {@code @AuthenticationPrincipal}. There is
 * no request parameter, header, or request body field anywhere in this API that a client can set
 * to influence it.
 *
 * <p>Implements {@link AuthenticatedPrincipal} so {@code Authentication.getName()} resolves to
 * {@link #username()} rather than falling back to this record's default {@code toString()}. Spring
 * Session JDBC persists {@code Authentication.getName()} into {@code
 * SPRING_SESSION.PRINCIPAL_NAME}, a {@code VARCHAR(100)} column; the full record representation
 * (every field, not just the username) overflows it.
 */
public record TarpeistoPrincipal(
        UUID userId, String username, String displayName, UUID organizationId, OrganizationRole role)
        implements Serializable, AuthenticatedPrincipal {

    @Override
    public String getName() {
        return username;
    }
}
