package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.OrganizationRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A read projection of one organization member for the Owner-only user-administration endpoints.
 * Deliberately excludes the password hash.
 */
public record UserSummaryView(
        UUID id,
        String username,
        String displayName,
        String email,
        boolean enabled,
        OrganizationRole role,
        Instant createdAt) {}
