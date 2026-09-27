package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/users/{userId}/external-identities}: an Owner-approved or
 * preconfigured link (ADR-0003's safe default), independent of automatic email matching.
 */
public record CreateExternalIdentityLinkRequest(
        @NotBlank String issuer, @NotBlank String subject) {}
