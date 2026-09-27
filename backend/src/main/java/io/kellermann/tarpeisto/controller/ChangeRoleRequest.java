package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.OrganizationRole;
import jakarta.validation.constraints.NotNull;

/** Request body for {@code PUT /api/v1/users/{userId}/role}. */
public record ChangeRoleRequest(@NotNull OrganizationRole role) {}
