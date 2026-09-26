package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Versioned caption/reorder command for one container layout image. */
public record UpdateLayoutMediaRequest(
        String caption,
        @Min(0) int displayOrder,
        boolean primaryImage,
        @NotNull Long version) {}
