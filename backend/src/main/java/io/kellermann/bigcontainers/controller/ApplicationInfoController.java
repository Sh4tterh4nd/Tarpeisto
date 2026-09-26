package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.config.ApplicationProperties;
import io.kellermann.bigcontainers.config.AuthenticationMode;
import io.kellermann.bigcontainers.config.AuthenticationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publicly reachable, unauthenticated application identification endpoint. */
@RestController
@RequestMapping("/api/v1/application")
public class ApplicationInfoController {

    private final ApplicationProperties applicationProperties;
    private final AuthenticationProperties authenticationProperties;
    private final BuildProperties buildProperties;

    public ApplicationInfoController(
            ApplicationProperties applicationProperties,
            AuthenticationProperties authenticationProperties,
            BuildProperties buildProperties) {
        this.applicationProperties = applicationProperties;
        this.authenticationProperties = authenticationProperties;
        this.buildProperties = buildProperties;
    }

    @GetMapping
    public ApplicationInfoResponse get() {
        boolean oidcConfigured = authenticationProperties.mode() == AuthenticationMode.LOCAL_AND_OIDC;
        ApplicationInfoResponse.OidcProviderInfo oidcProvider = oidcConfigured
                ? new ApplicationInfoResponse.OidcProviderInfo(
                        authenticationProperties.oidc().displayName(),
                        "/oauth2/authorization/" + AuthenticationProperties.OIDC_REGISTRATION_ID)
                : null;
        return new ApplicationInfoResponse(
                applicationProperties.displayName(), buildProperties.getVersion(), oidcConfigured, oidcProvider);
    }
}
