package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record CreateBookingRequest(
        UUID mutationId,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String clientText,
        @Size(max = 2000) String venueText,
        @Size(max = 20000) String notes,
        @NotNull Instant startsAt,
        @NotNull Instant endsAt) {}
