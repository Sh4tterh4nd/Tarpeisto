package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.BookingClaimType;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record BookingClaimCandidate(
        BookingClaimType type,
        UUID assetId,
        UUID assetModelId,
        UUID consumableStockId,
        BigDecimal quantity,
        UUID sourceBookingLineId,
        UUID containerId,
        UUID requirementId,
        Map<String, Object> snapshot) {
    public BookingClaimCandidate(
            BookingClaimType type, UUID assetId, UUID modelId, UUID stockId, BigDecimal quantity, UUID lineId) {
        this(type, assetId, modelId, stockId, quantity, lineId, null, null, Map.of());
    }
}
