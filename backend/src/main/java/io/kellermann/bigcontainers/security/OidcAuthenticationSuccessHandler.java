package io.kellermann.bigcontainers.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * Completes a successful OIDC login by replacing the {@code SecurityContext}'s {@code
 * Authentication} with a {@link BigContainersPrincipal}-only one - the exact same shape {@code
 * SessionAuthenticationService} persists after local login (ADR-0003: "Both local and OIDC
 * authentication end in the same Spring Session JDBC-backed application session ... [with the]
 * same principal").
 *
 * <p>Session-id rotation already happened before this handler runs: {@code
 * AbstractAuthenticationProcessingFilter} (which {@code http.oauth2Login(...)} uses) applies the
 * configured {@code SessionAuthenticationStrategy} - the same {@code
 * ChangeSessionIdAuthenticationStrategy} {@code SecurityConfiguration}'s {@code
 * .sessionFixation(...)} wires in - and persists the security context once on its own before
 * calling this handler. This handler's own {@link SecurityContextRepository#saveContext} call
 * simply re-saves into that same (already-rotated) session with the narrowed principal.
 */
@Component
class OidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private final SecurityContextRepository securityContextRepository;
    private final SecurityContextHolderStrategy securityContextHolderStrategy =
            SecurityContextHolder.getContextHolderStrategy();

    OidcAuthenticationSuccessHandler(SecurityContextRepository securityContextRepository) {
        this.securityContextRepository = securityContextRepository;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication.getPrincipal() instanceof ResolvedOidcUser resolvedOidcUser)) {
            // Defensive: only reachable if a differently configured OAuth2 login (not this
            // OIDC-only setup) somehow reused this handler.
            throw new IllegalStateException(
                    "Expected a ResolvedOidcUser principal after OIDC login, got " + authentication.getPrincipal());
        }
        BigContainersPrincipal principal = resolvedOidcUser.principal();
        Authentication sessionAuthentication = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));

        SecurityContext context = securityContextHolderStrategy.createEmptyContext();
        context.setAuthentication(sessionAuthentication);
        securityContextHolderStrategy.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // A same-origin SPA browser flow, not a JSON API response (ADR-0001/ADR-0003): the
        // session cookie is already set above, so redirecting to the SPA shell is all that is
        // needed for the browser to continue as an authenticated session.
        response.sendRedirect(request.getContextPath() + "/");
    }
}
