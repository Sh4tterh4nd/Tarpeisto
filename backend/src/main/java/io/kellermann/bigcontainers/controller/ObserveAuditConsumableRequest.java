package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.AuditConsumableStatus;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ObserveAuditConsumableRequest(
        @NotNull UUID operationId, @NotNull AuditConsumableStatus status, BigDecimal observedQuantity, String reason) {}
