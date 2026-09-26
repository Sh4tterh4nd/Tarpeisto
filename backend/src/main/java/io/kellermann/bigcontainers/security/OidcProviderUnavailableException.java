package io.kellermann.bigcontainers.security;

/**
 * The configured OIDC provider's issuer document could not be discovered (misconfigured issuer,
 * DNS failure, network/provider outage). Thrown only while handling one specific OIDC login
 * attempt (see {@link LazyOidcClientRegistrationRepository}) - it never touches local login, which
 * is exactly the anti-lockout guarantee ADR-0003 requires.
 */
public class OidcProviderUnavailableException extends RuntimeException {

    public OidcProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
