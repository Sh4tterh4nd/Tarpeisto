package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.util.UUID;

/** Response for {@code POST /api/v1/session} and {@code GET /api/v1/session}. */
public record SessionResponse(
        UUID userId, String username, String displayName, UUID organizationId, OrganizationRole role) {

    public static SessionResponse from(TarpeistoPrincipal principal) {
        return new SessionResponse(
                principal.userId(),
                principal.username(),
                principal.displayName(),
                principal.organizationId(),
                principal.role());
    }
}
