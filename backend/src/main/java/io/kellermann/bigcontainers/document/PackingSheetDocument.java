package io.kellermann.bigcontainers.document;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** Deterministic portrait-A4 packing sheets with two matching landscape-A5 panels per page. */
public final class PackingSheetDocument {
    private static final String REGULAR_FONT = "/document-fonts/RobotoMono-Regular.ttf";
    private static final String BOLD_FONT = "/document-fonts/RobotoMono-Bold.ttf";
    private static final float HALF_HEIGHT = PDRectangle.A4.getHeight() / 2.0f;
    private static final float MARGIN = 20.0f;
    private static final float QR_SIZE = 56.0f;
    private static final float CONTENT_BOTTOM = 18.0f;
    private static final float COLUMN_GUTTER = 12.0f;
    private static final int MAX_COLUMNS = 3;

    private PackingSheetDocument() {}

    public static byte[] render(PackingSheetSnapshot snapshot) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadFont(document, REGULAR_FONT);
            PDType0Font bold = loadFont(document, BOLD_FONT);
            Header header = header(snapshot, regular, bold);
            Layout layout = chooseLayout(snapshot, regular, bold, header.contentTop());
            List<List<Line>> pages = paginate(snapshot, layout, regular, bold);
            for (List<Line> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    drawHalf(content, snapshot, header, lines, layout, regular, bold, HALF_HEIGHT);
                    drawHalf(content, snapshot, header, lines, layout, regular, bold, 0.0f);
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the container packing-sheet PDF.", exception);
        }
    }

    private static Layout chooseLayout(
            PackingSheetSnapshot snapshot, PDType0Font regular, PDType0Font bold, float contentTop) throws IOException {
        // Fit order is intentionally stable: use every column count at 12pt before decreasing
        // the font, then paginate only after the minimum readable size has been considered.
        for (int fontSize = 12; fontSize >= 8; fontSize--) {
            for (int columns = 1; columns <= MAX_COLUMNS; columns++) {
                Layout layout = new Layout(fontSize, columns, contentTop);
                if (fitsOnOneHalf(lines(snapshot, layout, regular, bold), layout)) {
                    return layout;
                }
            }
        }
        return new Layout(8, MAX_COLUMNS, contentTop);
    }

    private static List<List<Line>> paginate(
            PackingSheetSnapshot snapshot, Layout layout, PDType0Font regular, PDType0Font bold) throws IOException {
        List<Line> lines = lines(snapshot, layout, regular, bold);
        List<List<Line>> pages = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        float used = 0.0f;
        int column = 0;
        for (Line line : lines) {
            if (!current.isEmpty() && used + line.height() > layout.contentHeight()) {
                column++;
                used = 0.0f;
                if (column == layout.columns()) {
                    pages.add(List.copyOf(current));
                    current.clear();
                    column = 0;
                }
            }
            current.add(line);
            used += line.height();
        }
        if (current.isEmpty()) {
            current.add(new Line("No direct packing requirements.", false, false, layout.lineHeight()));
        }
        pages.add(List.copyOf(current));
        return pages;
    }

    private static boolean fitsOnOneHalf(List<Line> lines, Layout layout) {
        int column = 1;
        float used = 0;
        for (Line line : lines) {
            if (line.height() > layout.contentHeight()) return false;
            if (used + line.height() > layout.contentHeight()) {
                column++;
                used = 0;
            }
            used += line.height();
        }
        return column <= layout.columns();
    }

    private static List<Line> lines(PackingSheetSnapshot snapshot, Layout layout, PDType0Font regular, PDType0Font bold)
            throws IOException {
        List<Line> result = new ArrayList<>();
        List<PackingSheetSnapshot.Requirement> ordinary = snapshot.requirements().stream()
                .filter(requirement -> requirement.kind() != PackingSheetSnapshot.Kind.EXACT)
                .toList();
        List<PackingSheetSnapshot.Requirement> exact = snapshot.requirements().stream()
                .filter(requirement -> requirement.kind() == PackingSheetSnapshot.Kind.EXACT)
                .toList();
        if (!ordinary.isEmpty()) {
            result.add(new Line("Packing requirements", true, true, layout.lineHeight()));
            for (PackingSheetSnapshot.Requirement requirement : ordinary) {
                result.addAll(wrap(
                        requirementText(requirement),
                        regular,
                        layout.fontSize(),
                        layout.columnWidth(),
                        layout.lineHeight()));
            }
        }
        if (!exact.isEmpty()) {
            result.add(new Line("Specific:", true, true, layout.lineHeight()));
            for (PackingSheetSnapshot.Requirement requirement : exact) {
                result.addAll(wrap(
                        requirement.exactAssetCode() + "  " + requirement.exactAssetName(),
                        regular,
                        layout.fontSize(),
                        layout.columnWidth(),
                        layout.lineHeight()));
            }
        }
        if (!snapshot.childContainers().isEmpty()) {
            result.add(new Line("Nested containers:", true, true, layout.lineHeight()));
            for (PackingSheetSnapshot.ChildContainer child : snapshot.childContainers()) {
                result.addAll(wrap(
                        child.publicCode() + "  " + child.name(),
                        regular,
                        layout.fontSize(),
                        layout.columnWidth(),
                        layout.lineHeight()));
            }
        }
        if (result.isEmpty()) {
            result.add(new Line("No direct packing requirements.", false, false, layout.lineHeight()));
        }
        return result;
    }

    private static String requirementText(PackingSheetSnapshot.Requirement requirement) {
        String quantity = number(requirement.quantity());
        return requirement.kind() == PackingSheetSnapshot.Kind.CONSUMABLE
                ? quantity + " " + requirement.stockUnit() + " " + requirement.modelName()
                : quantity + " x " + requirement.modelName();
    }

    private static List<Line> wrap(String source, PDType0Font font, float size, float width, float lineHeight)
            throws IOException {
        String text = source == null || source.isBlank()
                ? "-"
                : source.replaceAll("\\s+", " ").trim();
        List<Line> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.isEmpty()) {
                appendBrokenWord(lines, current, word, font, size, width, lineHeight);
            } else if (measure(font, size, current + " " + word) <= width) {
                current.append(' ').append(word);
            } else {
                lines.add(new Line(current.toString(), false, false, lineHeight));
                current.setLength(0);
                appendBrokenWord(lines, current, word, font, size, width, lineHeight);
            }
        }
        if (!current.isEmpty()) lines.add(new Line(current.toString(), false, false, lineHeight));
        return lines;
    }

    private static void appendBrokenWord(
            List<Line> lines,
            StringBuilder current,
            String word,
            PDType0Font font,
            float size,
            float width,
            float lineHeight)
            throws IOException {
        String remaining = word;
        while (measure(font, size, remaining) > width) {
            int split = largestFittingPrefix(remaining, font, size, width);
            lines.add(new Line(remaining.substring(0, split), false, false, lineHeight));
            remaining = remaining.substring(split);
        }
        current.append(remaining);
    }

    private static int largestFittingPrefix(String value, PDType0Font font, float size, float width)
            throws IOException {
        int low = 1;
        int high = value.length();
        while (low < high) {
            int candidate = (low + high + 1) / 2;
            if (measure(font, size, value.substring(0, candidate)) <= width) low = candidate;
            else high = candidate - 1;
        }
        return Math.max(1, low);
    }

    private static void drawHalf(
            PDPageContentStream content,
            PackingSheetSnapshot snapshot,
            Header header,
            List<Line> lines,
            Layout layout,
            PDType0Font regular,
            PDType0Font bold,
            float baseY)
            throws IOException {
        float width = PDRectangle.A4.getWidth();
        float barY = baseY + HALF_HEIGHT - header.barHeight();
        float[] color = color(snapshot.categoryColor());
        content.setNonStrokingColor(color[0], color[1], color[2]);
        content.addRect(0, barY, width, header.barHeight());
        content.fill();
        float[] contrast = readableText(color);
        content.setNonStrokingColor(contrast[0], contrast[1], contrast[2]);
        float titleY = baseY + HALF_HEIGHT - 24.0f;
        for (Line line : header.title()) {
            write(content, bold, 18.0f, MARGIN, titleY, line.text());
            titleY -= 20.0f;
        }
        titleY -= 3.0f;
        for (Line line : header.category()) {
            write(content, bold, 8.0f, MARGIN, titleY, line.text());
            titleY -= 10.0f;
        }
        titleY -= 2.0f;
        for (Line line : header.model()) {
            write(content, regular, 8.0f, MARGIN, titleY, line.text());
            titleY -= 10.0f;
        }
        float qrX = width - MARGIN - QR_SIZE;
        float qrY = baseY + HALF_HEIGHT - 70.0f;
        content.setNonStrokingColor(1f, 1f, 1f);
        content.addRect(qrX - 3.0f, qrY - 14.0f, QR_SIZE + 6.0f, QR_SIZE + 17.0f);
        content.fill();
        content.setNonStrokingColor(0f, 0f, 0f);
        drawQr(content, snapshot.containerCode(), qrX, qrY, QR_SIZE);
        write(content, bold, 8.0f, qrX, qrY - 10.0f, snapshot.containerCode());

        float columnWidth = layout.columnWidth();
        float y = baseY + layout.contentTop();
        int column = 0;
        for (Line line : lines) {
            if (y - line.height() < baseY + CONTENT_BOTTOM) {
                column++;
                y = baseY + layout.contentTop();
            }
            // Pagination guarantees this, even if a future layout changes capacity.
            if (column >= layout.columns()) {
                throw new IllegalStateException("Packing sheet page layout overflowed.");
            }
            float x = MARGIN + column * (columnWidth + COLUMN_GUTTER);
            write(content, line.bold() ? bold : regular, layout.fontSize(), x, y, line.text());
            y -= line.height();
        }
    }

    private static void drawQr(PDPageContentStream content, String value, float x, float y, float size)
            throws IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix;
        try {
            matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode packing-sheet QR code.", exception);
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

    private static void write(PDPageContentStream content, PDType0Font font, float size, float x, float y, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private static float measure(PDType0Font font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000.0f * size;
    }

    private static Header header(PackingSheetSnapshot snapshot, PDType0Font regular, PDType0Font bold)
            throws IOException {
        float width = PDRectangle.A4.getWidth() - MARGIN * 2 - QR_SIZE - 10.0f;
        List<Line> title = wrap(snapshot.containerName(), bold, 18.0f, width, 20.0f);
        List<Line> category = wrap(snapshot.categoryName(), bold, 8.0f, width, 10.0f);
        // Descriptions are free-form text: retain a measured three-line preview in the identity
        // bar so even a multi-paragraph description leaves room for the direct requirements.
        List<Line> model = wrap(snapshot.containerModel(), regular, 8.0f, width, 10.0f);
        if (model.size() > 3) {
            model = new ArrayList<>(model.subList(0, 3));
            String last = model.getLast().text();
            int prefix = largestFittingPrefix(last + "...", regular, 8.0f, width - measure(regular, 8.0f, "..."));
            model.set(2, new Line(last.substring(0, Math.min(prefix, last.length())) + "...", false, false, 10.0f));
        }
        float barHeight =
                Math.max(86.0f, 24.0f + title.size() * 20.0f + category.size() * 10.0f + model.size() * 10.0f + 12.0f);
        float contentTop = HALF_HEIGHT - barHeight - 12.0f;
        if (contentTop - CONTENT_BOTTOM < 11.0f) {
            throw new IllegalArgumentException("Container name and model are too long for an A5 packing-sheet header.");
        }
        return new Header(title, category, model, barHeight, contentTop);
    }

    private static PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        try (InputStream stream = PackingSheetDocument.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Bundled packing-sheet font is missing: " + resource);
            return PDType0Font.load(document, stream, true);
        }
    }

    private static float[] color(String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) return new float[] {0.2f, 0.2f, 0.2f};
        return new float[] {
            Integer.parseInt(hex.substring(1, 3), 16) / 255.0f,
            Integer.parseInt(hex.substring(3, 5), 16) / 255.0f,
            Integer.parseInt(hex.substring(5, 7), 16) / 255.0f
        };
    }

    private static float[] readableText(float[] rgb) {
        double luminance = 0.2126 * linear(rgb[0]) + 0.7152 * linear(rgb[1]) + 0.0722 * linear(rgb[2]);
        // Choose whichever of black/white has the greater WCAG contrast ratio.
        return (luminance + 0.05) / 0.05 >= 1.05 / (luminance + 0.05)
                ? new float[] {0f, 0f, 0f}
                : new float[] {1f, 1f, 1f};
    }

    private static double linear(float channel) {
        return channel <= 0.04045f ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record Line(String text, boolean bold, boolean heading, float height) {}

    private record Header(List<Line> title, List<Line> category, List<Line> model, float barHeight, float contentTop) {}

    private record Layout(int fontSize, int columns, float contentTop) {
        float lineHeight() {
            return fontSize + 3.0f;
        }

        float columnWidth() {
            return (PDRectangle.A4.getWidth() - 2 * MARGIN - (columns - 1) * COLUMN_GUTTER) / columns;
        }

        float contentHeight() {
            return contentTop - CONTENT_BOTTOM;
        }
    }
}
