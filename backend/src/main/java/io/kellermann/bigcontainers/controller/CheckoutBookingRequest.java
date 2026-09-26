package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CheckoutBookingRequest(
        @NotNull Long expectedVersion,
        @NotNull UUID mutationId,
        @Size(max = 20000) String overrideReason,
        List<UUID> selectedAssetIds) {}
