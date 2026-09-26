package io.kellermann.bigcontainers.document;

/** Supported A4 asset-label stocks, named after their measured label dimensions in millimetres. */
public enum AssetLabelFormat {
    A4_70X36_24(70.0f, 36.0f, 3, 8, 0.0f, 4.5f),
    A4_97X42_3_12(97.0f, 42.3f, 2, 6, 8.0f, 21.6f);

    private final float labelWidthMm;
    private final float labelHeightMm;
    private final int columns;
    private final int rows;
    private final float defaultMarginLeftMm;
    private final float defaultMarginTopMm;

    AssetLabelFormat(
            float labelWidthMm,
            float labelHeightMm,
            int columns,
            int rows,
            float defaultMarginLeftMm,
            float defaultMarginTopMm) {
        this.labelWidthMm = labelWidthMm;
        this.labelHeightMm = labelHeightMm;
        this.columns = columns;
        this.rows = rows;
        this.defaultMarginLeftMm = defaultMarginLeftMm;
        this.defaultMarginTopMm = defaultMarginTopMm;
    }

    public float labelWidthMm() {
        return labelWidthMm;
    }

    public float labelHeightMm() {
        return labelHeightMm;
    }

    public int columns() {
        return columns;
    }

    public int rows() {
        return rows;
    }

    public int labelsPerPage() {
        return columns * rows;
    }

    public float defaultMarginLeftMm() {
        return defaultMarginLeftMm;
    }

    public float defaultMarginTopMm() {
        return defaultMarginTopMm;
    }

    public float defaultHorizontalPitchMm() {
        return labelWidthMm;
    }

    public float defaultVerticalPitchMm() {
        return labelHeightMm;
    }
}
