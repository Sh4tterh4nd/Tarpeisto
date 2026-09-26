package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CheckInBookingAssetRequest(@NotNull UUID mutationId) {}
