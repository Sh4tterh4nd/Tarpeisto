package io.kellermann.bigcontainers.controller;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Ordered physical assets for Brother P-touch CSV output. */
public record CreatePtouchCsvRequest(
        @NotEmpty @Size(max = 500) List<@NotNull UUID> assetIds) {}
