package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.Condition;
import jakarta.validation.constraints.NotNull;

public record CloseRepairRequest(@NotNull Condition resultingCondition) {}
