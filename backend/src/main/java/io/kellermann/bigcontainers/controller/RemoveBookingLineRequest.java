package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record RemoveBookingLineRequest(
        @NotNull @PositiveOrZero Long expectedVersion) {}
