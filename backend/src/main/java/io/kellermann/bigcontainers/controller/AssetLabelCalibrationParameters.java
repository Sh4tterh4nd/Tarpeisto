package io.kellermann.bigcontainers.controller;

/** Optional measured A4-stock calibration values in millimetres. */
public record AssetLabelCalibrationParameters(
        Float marginLeftMm, Float marginTopMm, Float horizontalPitchMm, Float verticalPitchMm) {}
