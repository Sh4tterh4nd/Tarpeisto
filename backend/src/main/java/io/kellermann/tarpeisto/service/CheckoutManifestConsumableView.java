package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.model.CheckoutConsumableSemantics;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CheckoutManifestConsumableView(
        UUID id,
        BigDecimal quantity,
        BigDecimal returnedQuantity,
        BigDecimal consumedQuantity,
        Instant accountedAt,
        CheckoutConsumableSemantics semantics,
        Map<String, Object> snapshot) {}
