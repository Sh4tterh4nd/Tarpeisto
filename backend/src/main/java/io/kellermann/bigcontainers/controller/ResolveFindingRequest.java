package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.model.FindingResolutionAction;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ResolveFindingRequest(
        @NotNull UUID operationId,
        @NotNull FindingResolutionAction action,
        String note,
        UUID targetAssetId,
        UUID targetContainerAssetId,
        String repairReference) {}
