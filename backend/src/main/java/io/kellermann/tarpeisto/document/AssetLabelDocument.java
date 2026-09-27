package io.kellermann.tarpeisto.document;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** Deterministic vector PDF generation for A4 asset labels and calibration pages. */
public final class AssetLabelDocument {

    private static final float POINTS_PER_MILLIMETRE = 72.0f / 25.4f;
    private static final float A4_WIDTH_MM = 210.0f;
    private static final float A4_HEIGHT_MM = 297.0f;
    private static final String REGULAR_FONT = "/document-fonts/RobotoMono-Regular.ttf";
    private static final String BOLD_FONT = "/document-fonts/RobotoMono-Bold.ttf";

    private AssetLabelDocument() {}

    public static byte[] labels(
            List<AssetLabelEntry> entries,
            AssetLabelFormat format,
            int skipFirstPositions,
            AssetLabelCalibration calibration) {
        LabelGeometry geometry = LabelGeometry.from(format, calibration);
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadFont(document, REGULAR_FONT);
            PDType0Font bold = loadFont(document, BOLD_FONT);
            int position = skipFirstPositions;
            for (AssetLabelEntry entry : entries) {
                if (document.getNumberOfPages() == 0 || position % format.labelsPerPage() == 0) {
                    document.addPage(new PDPage(PDRectangle.A4));
                }
                PDPage page = document.getPage(document.getNumberOfPages() - 1);
                int pagePosition = position % format.labelsPerPage();
                drawLabel(document, page, entry, format, geometry, pagePosition, regular, bold);
                position++;
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the asset-label PDF.", exception);
        }
    }

    public static byte[] calibration(AssetLabelFormat format, AssetLabelCalibration calibration) {
        LabelGeometry geometry = LabelGeometry.from(format, calibration);
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            PDType0Font regular = loadFont(document, REGULAR_FONT);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                for (int position = 0; position < format.labelsPerPage(); position++) {
                    LabelBounds bounds = geometry.boundsFor(format, position);
                    content.setLineWidth(0.35f);
                    content.addRect(bounds.x(), bounds.y(), bounds.width(), bounds.height());
                    content.stroke();
                    float centerX = bounds.x() + bounds.width() / 2.0f;
                    float centerY = bounds.y() + bounds.height() / 2.0f;
                    content.moveTo(centerX - mm(3), centerY);
                    content.lineTo(centerX + mm(3), centerY);
                    content.moveTo(centerX, centerY - mm(3));
                    content.lineTo(centerX, centerY + mm(3));
                    content.stroke();
                    write(
                            content,
                            regular,
                            5.5f,
                            bounds.x() + mm(1.5f),
                            bounds.y() + bounds.height() - mm(3),
                            "R" + (position / format.columns() + 1) + " C" + (position % format.columns() + 1));
                }
                write(
                        content,
                        regular,
                        7.0f,
                        mm(8),
                        mm(7),
                        "Tarpeisto calibration - measure printed outlines; adjust margins and pitches in millimetres.");
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the calibration PDF.", exception);
        }
    }

    private static void drawLabel(
            PDDocument document,
            PDPage page,
            AssetLabelEntry entry,
            AssetLabelFormat format,
            LabelGeometry geometry,
            int position,
            PDType0Font regular,
            PDType0Font bold)
            throws IOException {
        LabelBounds bounds = geometry.boundsFor(format, position);
        try (PDPageContentStream content =
                new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            float padding = mm(2.0f);
            float qrSize =
                    Math.min(bounds.height() - 2 * padding, format == AssetLabelFormat.A4_70X36_24 ? mm(17) : mm(22));
            float qrX = bounds.x() + bounds.width() - padding - qrSize;
            float qrY = bounds.y() + (bounds.height() - qrSize) / 2.0f;
            drawQr(content, entry.publicCode(), qrX, qrY, qrSize);
            float textX = bounds.x() + padding;
            float textWidth = qrX - textX - mm(1.5f);
            float lineY = bounds.y() + bounds.height() - padding - 7.5f;
            write(
                    content,
                    regular,
                    5.5f,
                    textX,
                    lineY,
                    truncate(singleLine(entry.categoryName()), regular, 5.5f, textWidth));
            List<String> modelLines = twoLines(entry.modelName(), bold, 7.5f, textWidth);
            for (String line : modelLines) {
                lineY -= 9.0f;
                write(content, bold, 7.5f, textX, lineY, line);
            }
            String assetName =
                    entry.individualName() == null || entry.individualName().isBlank()
                            ? "#" + entry.unitNumber()
                            : singleLine(entry.individualName());
            lineY -= 8.0f;
            write(content, regular, 6.0f, textX, lineY, truncate(assetName, regular, 6.0f, textWidth));
            write(content, bold, 7.0f, qrX, qrY - 7.5f, entry.publicCode());
        }
    }

    private static void drawQr(PDPageContentStream content, String value, float x, float y, float size)
            throws IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        // Retain a one-module quiet zone inside the fixed visual QR square. The surrounding label
        // is normally white too, but keeping this margin makes cropped/printed labels reliably
        // decodable without changing the raw QR payload.
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix;
        try {
            matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode asset QR code.", exception);
        }
        float module = size / matrix.getWidth();
        for (int row = 0; row < matrix.getHeight(); row++) {
            for (int column = 0; column < matrix.getWidth(); column++) {
                if (matrix.get(column, row)) {
                    content.addRect(x + column * module, y + (matrix.getHeight() - row - 1) * module, module, module);
                }
            }
        }
        content.fill();
    }

    private static PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        try (InputStream stream = AssetLabelDocument.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Bundled label font is missing: " + resource);
            }
            return PDType0Font.load(document, stream, true);
        }
    }

    private static List<String> twoLines(String source, PDType0Font font, float fontSize, float width)
            throws IOException {
        String normalized = singleLine(source);
        if (normalized.isEmpty()) {
            return List.of("-");
        }
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        boolean secondLine = false;
        for (String word : normalized.split(" ")) {
            if (!secondLine) {
                String candidate = first.isEmpty() ? word : first + " " + word;
                if (measure(font, fontSize, candidate) <= width) {
                    first.setLength(0);
                    first.append(candidate);
                    continue;
                }
                secondLine = true;
            }
            String secondCandidate = second.isEmpty() ? word : second + " " + word;
            if (measure(font, fontSize, secondCandidate) <= width) {
                second.setLength(0);
                second.append(secondCandidate);
            } else {
                second.setLength(0);
                second.append(truncate(secondCandidate, font, fontSize, width));
                break;
            }
        }
        return second.isEmpty() ? List.of(first.toString()) : List.of(first.toString(), second.toString());
    }

    private static String truncate(String source, PDType0Font font, float fontSize, float width) throws IOException {
        if (measure(font, fontSize, source) <= width) {
            return source;
        }
        String ellipsis = "...";
        String candidate = source;
        while (!candidate.isEmpty() && measure(font, fontSize, candidate + ellipsis) > width) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate + ellipsis;
    }

    private static float measure(PDType0Font font, float fontSize, String value) throws IOException {
        return font.getStringWidth(value) / 1000.0f * fontSize;
    }

    private static String singleLine(String source) {
        return source == null ? "" : source.replaceAll("\\s+", " ").trim();
    }

    private static void write(PDPageContentStream content, PDType0Font font, float size, float x, float y, String value)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    private static float mm(float millimetres) {
        return millimetres * POINTS_PER_MILLIMETRE;
    }

    private record LabelGeometry(float marginLeft, float marginTop, float horizontalPitch, float verticalPitch) {
        static LabelGeometry from(AssetLabelFormat format, AssetLabelCalibration calibration) {
            AssetLabelCalibration resolved =
                    calibration == null ? AssetLabelCalibration.defaultsFor(format) : calibration;
            float marginLeft = resolved.marginLeftMm() == null ? format.defaultMarginLeftMm() : resolved.marginLeftMm();
            float marginTop = resolved.marginTopMm() == null ? format.defaultMarginTopMm() : resolved.marginTopMm();
            float horizontalPitch = resolved.horizontalPitchMm() == null
                    ? format.defaultHorizontalPitchMm()
                    : resolved.horizontalPitchMm();
            float verticalPitch =
                    resolved.verticalPitchMm() == null ? format.defaultVerticalPitchMm() : resolved.verticalPitchMm();
            validate(format, marginLeft, marginTop, horizontalPitch, verticalPitch);
            return new LabelGeometry(mm(marginLeft), mm(marginTop), mm(horizontalPitch), mm(verticalPitch));
        }

        LabelBounds boundsFor(AssetLabelFormat format, int position) {
            int row = position / format.columns();
            int column = position % format.columns();
            float x = marginLeft + column * horizontalPitch;
            float y = mm(A4_HEIGHT_MM) - marginTop - mm(format.labelHeightMm()) - row * verticalPitch;
            return new LabelBounds(x, y, mm(format.labelWidthMm()), mm(format.labelHeightMm()));
        }

        private static void validate(
                AssetLabelFormat format,
                float marginLeft,
                float marginTop,
                float horizontalPitch,
                float verticalPitch) {
            if (!Float.isFinite(marginLeft)
                    || !Float.isFinite(marginTop)
                    || !Float.isFinite(horizontalPitch)
                    || !Float.isFinite(verticalPitch)
                    || marginLeft < 0
                    || marginTop < 0
                    || horizontalPitch < format.labelWidthMm()
                    || verticalPitch < format.labelHeightMm()
                    || marginLeft + (format.columns() - 1) * horizontalPitch + format.labelWidthMm() > A4_WIDTH_MM
                    || marginTop + (format.rows() - 1) * verticalPitch + format.labelHeightMm() > A4_HEIGHT_MM) {
                throw new IllegalArgumentException(
                        "Calibration values place labels outside A4 or make labels overlap.");
            }
        }
    }

    private record LabelBounds(float x, float y, float width, float height) {}
}
