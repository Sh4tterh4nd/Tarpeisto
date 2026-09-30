package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.service.UserSummaryView;
import java.time.Instant;
import java.util.UUID;

/** Response element for the Owner-only user-administration endpoints. Never carries a password. */
public record UserResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        boolean enabled,
        OrganizationRole role,
        Instant createdAt,
        boolean archived,
        long version) {

    public static UserResponse from(UserSummaryView view) {
        return new UserResponse(
                view.id(),
                view.username(),
                view.displayName(),
                view.email(),
                view.enabled(),
                view.role(),
                view.createdAt(),
                view.archived(),
                view.version());
    }
}
