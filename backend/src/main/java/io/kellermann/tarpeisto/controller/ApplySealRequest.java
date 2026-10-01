package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ApplySealRequest(@NotNull @Min(0) Long expectedVersion) {}
