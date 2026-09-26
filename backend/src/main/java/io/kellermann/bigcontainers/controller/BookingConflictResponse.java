package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.BookingConflictView;
import java.math.BigDecimal;
import java.util.UUID;

public record BookingConflictResponse(
        String type,
        String message,
        UUID conflictingBookingId,
        UUID assetId,
        UUID stockId,
        UUID modelId,
        UUID containerId,
        UUID requirementId,
        BigDecimal requiredQuantity,
        BigDecimal availableQuantity) {
    static BookingConflictResponse from(BookingConflictView v) {
        return new BookingConflictResponse(
                v.type(),
                v.message(),
                v.conflictingBookingId(),
                v.assetId(),
                v.stockId(),
                v.modelId(),
                v.containerId(),
                v.requirementId(),
                v.requiredQuantity(),
                v.availableQuantity());
    }
}
