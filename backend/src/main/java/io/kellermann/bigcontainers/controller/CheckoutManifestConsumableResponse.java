package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.CheckoutConsumableSemantics;
import io.kellermann.bigcontainers.service.CheckoutManifestConsumableView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CheckoutManifestConsumableResponse(
        UUID id,
        BigDecimal quantity,
        BigDecimal returnedQuantity,
        BigDecimal consumedQuantity,
        Instant accountedAt,
        CheckoutConsumableSemantics semantics,
        Map<String, Object> snapshot) {
    static CheckoutManifestConsumableResponse from(CheckoutManifestConsumableView view) {
        return new CheckoutManifestConsumableResponse(
                view.id(),
                view.quantity(),
                view.returnedQuantity(),
                view.consumedQuantity(),
                view.accountedAt(),
                view.semantics(),
                view.snapshot());
    }
}
