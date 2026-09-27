package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;

public record PackingTemplateRequest(@NotBlank String name, String description) {}
