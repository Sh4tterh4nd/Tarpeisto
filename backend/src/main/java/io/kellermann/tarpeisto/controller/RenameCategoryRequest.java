package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code PUT /api/v1/categories/{categoryId}}. */
public record RenameCategoryRequest(
        @NotBlank String name, @NotBlank String color) {}
