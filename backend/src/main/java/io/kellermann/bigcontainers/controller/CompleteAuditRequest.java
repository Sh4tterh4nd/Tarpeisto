package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CompleteAuditRequest(
        @NotNull UUID operationId, @NotBlank String containerCode, boolean confirmMissing, Boolean sealConfirmed) {}
