package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.OrganizationRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body for {@code POST /api/v1/users}. */
public record CreateUserRequest(
        @NotBlank String username,

        @NotBlank @Size(min = 10, message = "password must be at least 10 characters") String password,

        @NotBlank String displayName,
        @Email String email,
        @NotNull OrganizationRole role) {}
