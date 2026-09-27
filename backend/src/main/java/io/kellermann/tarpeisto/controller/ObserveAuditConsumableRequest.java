package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.AuditConsumableStatus;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ObserveAuditConsumableRequest(
        @NotNull UUID operationId, @NotNull AuditConsumableStatus status, BigDecimal observedQuantity, String reason) {}
