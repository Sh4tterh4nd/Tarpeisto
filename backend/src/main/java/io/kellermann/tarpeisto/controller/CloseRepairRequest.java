package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.Condition;
import jakarta.validation.constraints.NotNull;

public record CloseRepairRequest(@NotNull Condition resultingCondition) {}
