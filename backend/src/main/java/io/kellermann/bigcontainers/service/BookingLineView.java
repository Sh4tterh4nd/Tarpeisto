package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.BookingLineType;
import java.math.BigDecimal;
import java.util.UUID;

public record BookingLineView(
        UUID id, BookingLineType type, UUID assetId, UUID consumableStockId, BigDecimal quantity, long version) {}
