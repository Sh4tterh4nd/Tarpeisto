package io.kellermann.bigcontainers.document;

/** Printer/stock measurements in millimetres. Values are validated before a document is rendered. */
public record AssetLabelCalibration(
        Float marginLeftMm, Float marginTopMm, Float horizontalPitchMm, Float verticalPitchMm) {

    public static AssetLabelCalibration defaultsFor(AssetLabelFormat format) {
        return new AssetLabelCalibration(
                format.defaultMarginLeftMm(),
                format.defaultMarginTopMm(),
                format.defaultHorizontalPitchMm(),
                format.defaultVerticalPitchMm());
    }
}
