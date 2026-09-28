package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record LaunchContainerAuditRequest(
        @NotBlank String containerCode, @NotNull UUID operationId) {}
