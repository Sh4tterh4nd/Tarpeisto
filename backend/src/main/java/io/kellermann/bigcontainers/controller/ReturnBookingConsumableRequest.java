package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ReturnBookingConsumableRequest(
        @NotNull UUID mutationId,
        @NotNull BigDecimal quantity,
        UUID destinationContainerAssetId,
        UUID destinationLocationId) {}
