package io.kellermann.tarpeisto.service;

import java.math.BigDecimal;
import java.util.UUID;

public record BookingConflictView(
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
    public BookingConflictView(String type, String message, UUID booking, UUID asset, UUID stock) {
        this(type, message, booking, asset, stock, null, null, null, null, null);
    }
}
