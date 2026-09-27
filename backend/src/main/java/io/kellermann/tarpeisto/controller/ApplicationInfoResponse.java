package io.kellermann.tarpeisto.controller;

/**
 * Response for {@code GET /api/v1/application}: publicly, anonymously reachable (see {@code
 * SecurityConfiguration}), so it must never carry a secret - in particular never the OIDC client
 * secret (ADR-0003, specification section 28).
 *
 * @param applicationName the organization-facing display name of this installation
 * @param version the deployed application version
 * @param oidcConfigured whether this installation's deployment mode is {@code LOCAL_AND_OIDC}
 *     ({@code io.kellermann.tarpeisto.config.AuthenticationMode}); {@code false} in {@code
 *     LOCAL_ONLY} mode
 * @param oidcProvider the minimal, non-secret information the SPA needs to render a provider
 *     sign-in button, or {@code null} when {@code oidcConfigured} is {@code false}
 */
public record ApplicationInfoResponse(
        String applicationName, String version, boolean oidcConfigured, OidcProviderInfo oidcProvider) {

    /**
     * @param displayName the provider's user-facing name (ADR-0003 provider configuration)
     * @param authorizationEndpoint the same-origin path a "Sign in with {@code displayName}"
     *     button navigates the browser to, starting the OIDC authorization-code flow
     */
    public record OidcProviderInfo(String displayName, String authorizationEndpoint) {}
}
