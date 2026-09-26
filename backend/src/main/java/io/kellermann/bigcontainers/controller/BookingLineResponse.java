package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.BookingLineType;
import io.kellermann.bigcontainers.service.BookingLineView;
import java.math.BigDecimal;
import java.util.UUID;

public record BookingLineResponse(
        UUID id, BookingLineType type, UUID assetId, UUID consumableStockId, BigDecimal quantity, long version) {
    static BookingLineResponse from(BookingLineView view) {
        return new BookingLineResponse(
                view.id(), view.type(), view.assetId(), view.consumableStockId(), view.quantity(), view.version());
    }
}
