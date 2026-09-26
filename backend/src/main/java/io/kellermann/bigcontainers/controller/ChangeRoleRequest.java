package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.OrganizationRole;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code PUT /api/v1/users/{userId}/role}. */
public record ChangeRoleRequest(@NotNull OrganizationRole role) {}
