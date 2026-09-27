package io.kellermann.tarpeisto.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateBookingRequest(
        @NotNull Long expectedVersion, @Valid @NotNull CreateBookingRequest booking) {}
