package io.kellermann.bigcontainers.service;

import io.kellermann.bigcontainers.model.CheckoutConsumableSemantics;
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
