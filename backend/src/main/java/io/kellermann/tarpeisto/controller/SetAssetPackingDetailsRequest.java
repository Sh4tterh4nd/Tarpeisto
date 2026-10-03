package io.kellermann.tarpeisto.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetAssetPackingDetailsRequest(
        @NotBlank String containerColor,
        @Size(max = 500) String unitDescription,
        @NotNull @Min(0) Long expectedVersion) {}
