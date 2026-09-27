package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ScanAuditRequest(
        @NotNull UUID operationId, @NotBlank String code) {}
