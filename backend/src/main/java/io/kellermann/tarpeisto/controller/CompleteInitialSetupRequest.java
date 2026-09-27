package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for the installation's one-time {@code POST /api/v1/setup}. */
public record CompleteInitialSetupRequest(
        @NotBlank @Size(max = 255) String organizationName,
        @NotBlank @Size(max = 255) String username,

        @NotBlank @Size(min = 10, max = 255, message = "password must be between 10 and 255 characters") String password,

        @NotBlank @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email) {}
