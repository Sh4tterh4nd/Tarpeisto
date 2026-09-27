package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.AuditFindingType;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record RecordAuditFindingRequest(
        @NotNull UUID operationId, @NotNull AuditFindingType type, UUID assetId, String note) {}
