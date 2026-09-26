package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/session}. */
public record LoginRequest(
        @NotBlank String username, @NotBlank String password) {}
