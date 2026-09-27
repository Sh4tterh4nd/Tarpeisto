package io.kellermann.tarpeisto.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-wide, environment-supplied settings that exist as of Phase 0.
 *
 * <p>Bound from {@code tarpeisto.application.*}. Values come from environment variables in
 * every real deployment (for example {@code TARPEISTO_APPLICATION_DISPLAY_NAME}); a
 * misconfigured deployment fails fast at startup because these fields are validated.
 *
 * @param displayName the organization-facing application name shown in the UI and returned by
 *     {@code GET /api/v1/application}
 * @param publicBaseUrl the externally reachable HTTPS origin of this installation, required by a
 *     later phase to generate correct OIDC redirect URIs behind a reverse proxy; optional in
 *     Phase 0 because no redirect URIs are generated yet
 */
@ConfigurationProperties(prefix = "tarpeisto.application")
@Validated
public record ApplicationProperties(@NotBlank String displayName, URI publicBaseUrl) {

    @AssertTrue(message = "tarpeisto.application.public-base-url must be an absolute URL when set") public boolean isPublicBaseUrlAbsoluteOrAbsent() {
        return publicBaseUrl == null || publicBaseUrl.isAbsolute();
    }
}
