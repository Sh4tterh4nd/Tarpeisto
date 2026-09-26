package io.kellermann.bigcontainers.security;

import io.kellermann.bigcontainers.config.AuthenticationProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

/**
 * Resolves the single generic OIDC provider's {@link ClientRegistration} by issuer discovery
 * (ADR-0003) lazily, on first use, rather than eagerly at application startup.
 *
 * <p>This is the anti-lockout seam (specification section 28, implementation plan section 3.3:
 * "Provider failure does not prevent an enabled local Owner from signing in"). {@link
 * ClientRegistrations#fromIssuerLocation(String)} makes a blocking HTTP call to the provider's
 * {@code /.well-known/openid-configuration} document. If that call happened eagerly while building
 * this bean during context refresh - which is what a plain {@code ClientRegistrationRepository}
 * bean built from {@code OAuth2ClientProperties} would do - a provider outage or DNS failure at
 * boot time would fail the entire application, taking local login (and therefore every Owner's
 * only recovery path) down with it. Discovery is instead deferred until a browser actually starts
 * an OIDC login ({@code GET /oauth2/authorization/oidc}); local login never touches this class.
 *
 * <p>A successful discovery is memoized for the life of the application context (the provider's
 * document is not expected to change). A failed discovery is deliberately <strong>not</strong>
 * cached, so the provider recovering later (or a corrected DNS/network condition) is picked up by
 * the next login attempt without an application restart.
 */
class LazyOidcClientRegistrationRepository implements ClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(LazyOidcClientRegistrationRepository.class);

    private final AuthenticationProperties.OidcProvider oidcProperties;
    private final AtomicReference<ClientRegistration> resolved = new AtomicReference<>();

    LazyOidcClientRegistrationRepository(AuthenticationProperties.OidcProvider oidcProperties) {
        this.oidcProperties = oidcProperties;
    }

    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        if (!AuthenticationProperties.OIDC_REGISTRATION_ID.equals(registrationId)) {
            return null;
        }
        ClientRegistration existing = resolved.get();
        if (existing != null) {
            return existing;
        }
        ClientRegistration discovered = discover();
        resolved.compareAndSet(null, discovered);
        return resolved.get();
    }

    private ClientRegistration discover() {
        try {
            return ClientRegistrations.fromIssuerLocation(
                            oidcProperties.issuerUri().toString())
                    .registrationId(AuthenticationProperties.OIDC_REGISTRATION_ID)
                    .clientId(oidcProperties.clientId())
                    .clientSecret(oidcProperties.clientSecret())
                    .scope(oidcProperties.scopes())
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .userNameAttributeName(IdTokenClaimNames.SUB)
                    .clientName(oidcProperties.displayName())
                    .build();
        } catch (RuntimeException exception) {
            // Deliberately not rethrown to the framework here: findByRegistrationId returning
            // null (below, via the caller receiving this exception) would be misleading - the
            // registration id IS configured, it is simply unreachable right now. Propagating the
            // original exception lets it surface as this specific login attempt's failure
            // (handled by OidcAuthenticationFailureHandler / SecurityConfiguration's
            // AuthenticationEntryPoint) without affecting local login or any other request.
            log.warn(
                    "OIDC provider discovery failed for issuer '{}'; this OIDC login attempt will fail, "
                            + "but local login is unaffected.",
                    oidcProperties.issuerUri(),
                    exception);
            throw new OidcProviderUnavailableException(
                    "The OpenID Connect provider is not reachable right now.", exception);
        }
    }
}
