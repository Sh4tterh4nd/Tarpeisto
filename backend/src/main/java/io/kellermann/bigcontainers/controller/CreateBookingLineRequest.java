package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.BookingLineType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record CreateBookingLineRequest(
        @NotNull Long expectedBookingVersion,
        @NotNull BookingLineType type,
        UUID assetId,
        UUID consumableStockId,
        @NotNull BigDecimal quantity) {}
