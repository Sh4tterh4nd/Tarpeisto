package io.kellermann.tarpeisto.security;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * The single place that decides how an OIDC-phase failure is reported to the client: the same RFC
 * 9457 {@code application/problem+json} shape every other error path in this API already returns,
 * never Spring Boot's default, non-problem-details {@code /error} body. Shared by the two places
 * such a failure can surface (see each caller's Javadoc for why both are needed):
 *
 * <ul>
 *   <li>{@link OidcAuthorizationRequestFailureHandler}, for {@code GET /oauth2/authorization/oidc}
 *   <li>{@link OidcProviderUnavailableExceptionFilter}, as a defensive safety net around the
 *       {@code /login/oauth2/code/oidc} callback
 * </ul>
 *
 * <p>{@link OidcProviderUnavailableException} (the provider could not be reached - see {@link
 * LazyOidcClientRegistrationRepository}) maps to {@code 503}, with a {@code detail} that
 * explicitly tells the caller local sign-in still works: the actionable information an
 * administrator needs during a provider outage (implementation plan section 18: "expose
 * actionable health diagnostics"). Anything else unexpected maps to a generic {@code 500}. Neither
 * response body includes the issuer URI, client id, or a stack trace - {@link
 * LazyOidcClientRegistrationRepository} and this class log that server-side instead.
 */
final class OidcFailureResponseWriter {

    private static final Logger log = LoggerFactory.getLogger(OidcFailureResponseWriter.class);

    private final ProblemDetailSecurityResponseWriter responseWriter;

    OidcFailureResponseWriter(ProblemDetailSecurityResponseWriter responseWriter) {
        this.responseWriter = responseWriter;
    }

    /** {@code exception}'s full cause chain is inspected, since Spring Security's own filters re-wrap it. */
    void write(HttpServletResponse response, Throwable exception) throws IOException {
        if (response.isCommitted()) {
            log.error("Unable to report an OIDC-phase failure: the response was already committed.", exception);
            return;
        }
        if (findCause(exception, OidcProviderUnavailableException.class) != null) {
            responseWriter.write(
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OIDC_PROVIDER_UNAVAILABLE",
                    "OpenID Connect Provider Unavailable",
                    "The single sign-on provider could not be reached. Local username/password sign-in remains"
                            + " available.");
        } else {
            log.error("Unexpected failure while processing an OIDC login request.", exception);
            responseWriter.write(
                    response,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "OIDC_SIGN_IN_FAILED",
                    "OIDC Sign-In Failed",
                    "An unexpected error occurred while processing the sign-in request. Local username/password"
                            + " sign-in remains available.");
        }
    }

    private static <T extends Throwable> T findCause(Throwable exception, Class<T> causeType) {
        Throwable current = exception;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return causeType.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }
}
