package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.OrganizationRole;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import java.util.UUID;

/** Response for {@code POST /api/v1/session} and {@code GET /api/v1/session}. */
public record SessionResponse(
        UUID userId, String username, String displayName, UUID organizationId, OrganizationRole role) {

    public static SessionResponse from(BigContainersPrincipal principal) {
        return new SessionResponse(
                principal.userId(),
                principal.username(),
                principal.displayName(),
                principal.organizationId(),
                principal.role());
    }
}
