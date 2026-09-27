package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.CheckoutConsumableSemantics;
import io.kellermann.tarpeisto.service.CheckoutManifestConsumableView;
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
