package io.kellermann.bigcontainers.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * Reports a failure starting an OIDC login (most importantly, {@link
 * OidcProviderUnavailableException} from lazy issuer discovery - see {@link
 * LazyOidcClientRegistrationRepository}) as RFC 9457 {@code application/problem+json}, via {@link
 * OidcFailureResponseWriter}.
 *
 * <p>This exists because {@code OAuth2AuthorizationRequestRedirectFilter} (handling {@code GET
 * /oauth2/authorization/oidc}) does <strong>not</strong> let such a failure propagate up the
 * filter chain the way an ordinary uncaught exception would: it catches whatever {@code
 * OAuth2AuthorizationRequestResolver#resolve} throws internally, wraps it in its own {@code
 * OAuth2AuthorizationRequestException}, and hands it directly to its {@code
 * authenticationFailureHandler} - by default, a handler that calls {@code
 * response.sendError(500, "Internal Server Error")} and returns, which is exactly Spring Boot's
 * default, non-problem-details {@code /error} body (the defect this class fixes). There is no hook
 * for this on the {@code http.oauth2Login(...)} DSL's {@code AuthorizationEndpointConfig}, so
 * {@code SecurityConfiguration} installs this handler directly on the built filter instance via
 * {@code OAuth2AuthorizationRequestRedirectFilter}'s public {@code setAuthenticationFailureHandler}
 * ({@code @since} Spring Security 6.3) after {@code http.build()}.
 *
 * <p>{@link OidcProviderUnavailableExceptionFilter} is the analogous fix for the {@code
 * /login/oauth2/code/oidc} callback filter, which has no such built-in "swallow and sendError"
 * behavior and so only needs an ordinary wrapping {@code try/catch}.
 */
class OidcAuthorizationRequestFailureHandler implements AuthenticationFailureHandler {

    private final OidcFailureResponseWriter failureResponseWriter;

    OidcAuthorizationRequestFailureHandler(OidcFailureResponseWriter failureResponseWriter) {
        this.failureResponseWriter = failureResponseWriter;
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        failureResponseWriter.write(response, exception);
    }
}
