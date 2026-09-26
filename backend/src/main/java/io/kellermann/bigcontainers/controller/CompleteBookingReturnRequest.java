package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CompleteBookingReturnRequest(@NotNull UUID mutationId) {}
