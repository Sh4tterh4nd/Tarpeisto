package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record OpenRepairRequest(
        UUID sourceFindingId, @NotBlank String referenceOrDescription) {}
