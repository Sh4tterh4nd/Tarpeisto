package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/categories}. */
public record CreateCategoryRequest(
        @NotBlank String name, @NotBlank String color) {}
