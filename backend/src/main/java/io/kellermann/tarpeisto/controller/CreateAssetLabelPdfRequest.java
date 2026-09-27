package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.document.AssetLabelFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Ordered asset selection and optional calibration for an A4 label export. */
public record CreateAssetLabelPdfRequest(
        @NotEmpty @Size(max = 500) List<@NotNull UUID> assetIds,
        @NotNull AssetLabelFormat format,
        @Min(0) @Max(23) int skipFirstPositions,
        AssetLabelCalibrationParameters calibration) {}
