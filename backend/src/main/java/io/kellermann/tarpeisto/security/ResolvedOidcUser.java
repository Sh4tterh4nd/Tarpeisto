package io.kellermann.tarpeisto.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Wraps the delegate {@link OidcUser} that {@link TarpeistoOidcUserService} loaded from the
 * provider, carrying the already-resolved {@link TarpeistoPrincipal} alongside it.
 *
 * <p>This is a short-lived object: {@code OAuth2LoginAuthenticationProvider} places it as the
 * principal of the {@code Authentication} it builds, and {@link OidcAuthenticationSuccessHandler}
 * immediately reads {@link #principal()} back off of it and replaces the {@code SecurityContext}'s
 * {@code Authentication} with a plain {@link TarpeistoPrincipal}-only one - the same shape a
 * local login session gets - before any controller ever runs. Nothing downstream of that handler
 * ever sees this class.
 */
final class ResolvedOidcUser implements OidcUser {

    private final OidcUser delegate;
    private final TarpeistoPrincipal principal;
    private final Collection<? extends GrantedAuthority> authorities;

    ResolvedOidcUser(OidcUser delegate, TarpeistoPrincipal principal) {
        this.delegate = delegate;
        this.principal = principal;
        this.authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
    }

    TarpeistoPrincipal principal() {
        return principal;
    }

    @Override
    public Map<String, Object> getClaims() {
        return delegate.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return delegate.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return delegate.getIdToken();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return principal.username();
    }
}
