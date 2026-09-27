package io.kellermann.tarpeisto.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Permanent-user authentication configuration (ADR-0003, specification section 4.3, implementation
 * plan section 3.2). Bound from {@code tarpeisto.authentication.*}.
 *
 * <p>Selecting {@link AuthenticationMode#LOCAL_AND_OIDC} without a usable provider configuration
 * fails application startup with {@link #isOidcConfigurationUsableWhenRequired()} rather than
 * starting a half-configured application: {@code SecurityConfiguration} only calls {@code
 * http.oauth2Login(...)} once this record has already passed validation, so a filter chain is
 * never wired against a display name, issuer, client id, client secret, or scope list that could
 * silently be blank.
 *
 * <p>{@link #autoLinkByEmail()} is the Owner's explicit opt-in to automatic email-based linking
 * (ADR-0003 "Linking policy"); it defaults to {@code false} ("Owner-approved or preconfigured
 * linking is the safe default"). There is deliberately no runtime/per-organization toggle for it
 * yet - see the OIDC task report for that scope decision.
 *
 * @param mode the deployment mode; local login is always available in both modes
 * @param oidc the single generic OIDC provider's configuration, required and validated only when
 *     {@code mode} is {@link AuthenticationMode#LOCAL_AND_OIDC}
 * @param autoLinkByEmail whether an Owner has enabled automatic verified-email linking
 */
@ConfigurationProperties(prefix = "tarpeisto.authentication")
@Validated
public record AuthenticationProperties(@NotNull AuthenticationMode mode, OidcProvider oidc, boolean autoLinkByEmail) {

    /**
     * The fixed Spring Security OAuth2 client registration id for the single generic OIDC
     * provider. Spring's default endpoints follow from it: the authorization entry point is
     * {@code /oauth2/authorization/oidc} and the callback is {@code /login/oauth2/code/oidc}
     * (used by {@code SecurityConfiguration}, {@code ApplicationInfoController}, and the OIDC
     * client-registration wiring).
     */
    public static final String OIDC_REGISTRATION_ID = "oidc";

    @AssertTrue(
            message = "tarpeisto.authentication.oidc (display-name, issuer-uri, client-id, client-secret, and at"
                    + " least one scope) must be fully set when tarpeisto.authentication.mode is"
                    + " LOCAL_AND_OIDC")
    public boolean isOidcConfigurationUsableWhenRequired() {
        if (mode != AuthenticationMode.LOCAL_AND_OIDC) {
            return true;
        }
        return oidc != null
                && isNotBlank(oidc.displayName())
                && oidc.issuerUri() != null
                && oidc.issuerUri().isAbsolute()
                && isNotBlank(oidc.clientId())
                && isNotBlank(oidc.clientSecret())
                && oidc.scopes() != null
                && !oidc.scopes().isEmpty();
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * @param displayName the provider's user-facing name (for example shown on a "Sign in with
     *     ..." button); never secret
     * @param issuerUri the provider's OIDC issuer, used for issuer discovery (ADR-0003: "Use
     *     issuer discovery")
     * @param clientId the registered OAuth2 client id; never secret
     * @param clientSecret the registered OAuth2 client secret; supplied only through environment
     *     variables or a mounted secret file, never committed, and never returned by any API
     *     response
     * @param scopes the scopes requested at authorization time; defaults to {@code openid profile
     *     email} in {@code application.yml}
     */
    public record OidcProvider(
            String displayName, URI issuerUri, String clientId, String clientSecret, List<String> scopes) {}
}
