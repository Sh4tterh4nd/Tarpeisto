package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record OpenRepairRequest(
        UUID sourceFindingId, @NotBlank String referenceOrDescription) {}
