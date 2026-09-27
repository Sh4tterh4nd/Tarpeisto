package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotNull;

/** Request body for {@code PUT /api/v1/users/{userId}/enabled}. */
public record SetEnabledRequest(@NotNull Boolean enabled) {}
