package io.kellermann.bigcontainers.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record PackingRequirementMutationRequest(
        long expectedVersion, @NotNull @Valid PackingRequirementRequest requirement) {}
