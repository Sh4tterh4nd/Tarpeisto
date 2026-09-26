package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST .../custom-fields/{fieldId}/options}. */
public record CreateModelCustomFieldOptionRequest(@NotBlank String value) {}
