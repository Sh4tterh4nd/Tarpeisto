package io.kellermann.tarpeisto.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A defensive safety net around {@code /login/oauth2/code/oidc}: turns a {@code RuntimeException}
 * that genuinely propagates out of {@code OAuth2LoginAuthenticationFilter} into the same RFC 9457
 * {@code application/problem+json} shape every other error path in this API returns, via {@link
 * OidcFailureResponseWriter}, instead of letting it escape as Spring Boot's default {@code /error}
 * body.
 *
 * <p>Unlike {@code OAuth2AuthorizationRequestRedirectFilter} (see {@link
 * OidcAuthorizationRequestFailureHandler}'s Javadoc), {@code OAuth2LoginAuthenticationFilter} does
 * not swallow and {@code sendError} internally: an unexpected {@code RuntimeException} it does not
 * itself recognize as an {@code AuthenticationException} - most plausibly an infrastructure failure
 * inside {@link io.kellermann.tarpeisto.service.ExternalIdentityService#resolveLogin} (for
 * example a database outage during login resolution), reached via {@code
 * TarpeistoOidcUserService} - genuinely propagates up the filter chain uncaught, since {@code
 * AbstractAuthenticationProcessingFilter}'s {@code doFilter} only catches {@code
 * AuthenticationException}. This filter is registered early enough in the chain to catch it there.
 *
 * <p>{@link OidcProviderUnavailableException} specifically is also caught here for completeness
 * (for example a theoretical race where issuer discovery had not yet been memoized - see {@link
 * LazyOidcClientRegistrationRepository} - by the time a callback with a still-valid stored
 * authorization request arrives), even though in practice a memoized successful discovery is what
 * got the browser redirected to the provider in the first place.
 */
class OidcProviderUnavailableExceptionFilter extends OncePerRequestFilter {

    private final OidcFailureResponseWriter failureResponseWriter;

    OidcProviderUnavailableExceptionFilter(OidcFailureResponseWriter failureResponseWriter) {
        this.failureResponseWriter = failureResponseWriter;
    }

    /**
     * Scoped to only the OIDC callback path this class exists for. This filter sits early in the
     * <em>global</em> security chain (it must, to wrap {@code OAuth2LoginAuthenticationFilter}),
     * so without this narrowing its {@code catch (RuntimeException ...)} safety net would also
     * wrap every other request the chain ultimately serves - including ordinary {@code
     * @RestController} traffic already correctly handled by {@code ApplicationExceptionHandler}/
     * Spring MVC's problem-details resolution - and could mislabel an unrelated failure as an OIDC
     * sign-in failure.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/login/oauth2/code/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } catch (RuntimeException exception) {
            if (response.isCommitted()) {
                // Nothing left to do but let it surface as a container-level error; a response
                // cannot be rewritten once headers/body have already been sent.
                throw exception;
            }
            failureResponseWriter.write(response, exception);
        }
    }
}
