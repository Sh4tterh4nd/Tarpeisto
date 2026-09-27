package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.document.AssetLabelFormat;
import jakarta.validation.constraints.NotNull;

/** A print-and-measure alignment sheet for one supported A4 label stock. */
public record CreateAssetLabelCalibrationRequest(
        @NotNull AssetLabelFormat format, AssetLabelCalibrationParameters calibration) {}
