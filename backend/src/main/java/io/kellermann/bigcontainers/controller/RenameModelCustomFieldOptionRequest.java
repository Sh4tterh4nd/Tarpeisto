package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code PUT .../custom-fields/{fieldId}/options/{optionId}}. */
public record RenameModelCustomFieldOptionRequest(@NotBlank String value) {}
