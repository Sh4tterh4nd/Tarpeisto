package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ReserveBookingRequest(@NotNull @PositiveOrZero Long expectedVersion) {}
