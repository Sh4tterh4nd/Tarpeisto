package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.AuditFindingType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RecordAuditFindingRequest(
        @NotNull UUID operationId, @NotNull AuditFindingType type, UUID assetId, String note) {}
