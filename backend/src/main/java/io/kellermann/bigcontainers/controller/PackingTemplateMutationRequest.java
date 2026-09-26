package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

public record PackingTemplateMutationRequest(
        long expectedVersion, @NotBlank String name, String description) {}
