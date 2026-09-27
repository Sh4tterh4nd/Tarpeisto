package io.kellermann.tarpeisto.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Turns a failed OIDC login into a same-origin SPA redirect carrying a stable, non-sensitive
 * machine-readable error code query parameter, per ADR-0003: "Ambiguous, missing, or unverified
 * email claims stop with a clear request for Owner assistance" rather than a stack trace or a
 * guess.
 *
 * <p>This handler is only ever consulted by {@code AbstractAuthenticationProcessingFilter} for a
 * genuine {@link AuthenticationException} raised <strong>during</strong> an authentication attempt:
 * a denied linking decision from {@link TarpeistoOidcUserService} (a stable {@code
 * OAuth2Error} code such as {@code EMAIL_NOT_VERIFIED} or {@code AMBIGUOUS_EMAIL_MATCH}), or a
 * genuine {@link OAuth2AuthenticationException} from Spring Security's own OAuth2 client machinery
 * (for example a token-exchange failure or an ID token that fails validation).
 *
 * <p>It does <strong>not</strong> see {@link OidcProviderUnavailableException} (thrown by {@link
 * LazyOidcClientRegistrationRepository} when the provider cannot be reached at all): that exception
 * is deliberately a plain {@link RuntimeException}, not an {@code AuthenticationException} - the
 * provider being unreachable is not itself a failed authentication *attempt*, since one was never
 * even started. {@code GET /oauth2/authorization/oidc} (where discovery is actually attempted) is
 * handled by {@link OidcAuthorizationRequestFailureHandler} instead - a plain {@code
 * RuntimeException} there never reaches an {@code AuthenticationFailureHandler} at all through the
 * normal route, see that class's Javadoc - and {@link OidcProviderUnavailableExceptionFilter} is a
 * defensive safety net for the same class of failure surfacing from the {@code
 * /login/oauth2/code/oidc} callback filter instead. Both report an RFC 9457 {@code
 * application/problem+json} response rather than a redirect, since {@code
 * /oauth2/authorization/oidc} is reachable directly (for example a same-origin fetch from the SPA,
 * not only a top-level browser navigation).
 */
@Component
class OidcAuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final String DEFAULT_ERROR_CODE = "OIDC_SIGN_IN_FAILED";

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String errorCode = DEFAULT_ERROR_CODE;
        if (exception instanceof OAuth2AuthenticationException oauth2Exception
                && oauth2Exception.getError() != null
                && oauth2Exception.getError().getErrorCode() != null) {
            errorCode = oauth2Exception.getError().getErrorCode();
        }
        String location =
                request.getContextPath() + "/?oidcError=" + URLEncoder.encode(errorCode, StandardCharsets.UTF_8);
        response.sendRedirect(location);
    }
}
