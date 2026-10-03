package io.kellermann.tarpeisto.document;

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

/** One portrait A4 page contains two identical, measured landscape A5 packing sheets. */
public final class PackingSheetDocument {
    private static final String REGULAR_FONT = "/document-fonts/RobotoMono-Regular.ttf";
    private static final String BOLD_FONT = "/document-fonts/RobotoMono-Bold.ttf";
    private static final float HALF_HEIGHT = PDRectangle.A4.getHeight() / 2;
    private static final float MARGIN = 20;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;

    private PackingSheetDocument() {}

    public static byte[] render(PackingSheetSnapshot snapshot) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadFont(document, REGULAR_FONT);
            PDType0Font bold = loadFont(document, BOLD_FONT);
            List<Entry> entries = entries(snapshot);
            Layout layout = fit(snapshot, entries, regular, bold);
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                drawHalf(content, snapshot, layout, regular, bold, HALF_HEIGHT);
                drawHalf(content, snapshot, layout, regular, bold, 0);
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the container packing-sheet PDF.", exception);
        }
    }

    private static List<Entry> entries(PackingSheetSnapshot snapshot) {
        List<Entry> result = new ArrayList<>();
        for (var r : snapshot.requirements()) {
            boolean exact = r.kind() == PackingSheetSnapshot.Kind.EXACT;
            String quantity = exact ? "1" : number(r.quantity());
            if (r.kind() == PackingSheetSnapshot.Kind.CONSUMABLE) quantity += " " + r.stockUnit();
            result.add(new Entry(
                    quantity, exact ? r.exactAssetName() : r.modelName(), exact ? r.exactAssetCode() : "", false));
        }
        for (var child : snapshot.childContainers()) result.add(new Entry("1", child.name(), child.publicCode(), true));
        return List.copyOf(result);
    }

    private static Layout fit(PackingSheetSnapshot snapshot, List<Entry> entries, PDType0Font regular, PDType0Font bold)
            throws IOException {
        int columns = entries.size() > 15 ? 2 : 1;
        int split = entries.size() <= 30 ? Math.min(15, entries.size()) : (entries.size() + 1) / 2;
        // Every font, padding, heading and QR shrinks together; no minimum text size or hidden rows.
        for (float scale = 1; scale > 0; scale *= .96f) {
            float padding = 6 * scale, gap = 5 * scale, font = 11 * scale, leading = 13 * scale;
            float qr = 58 * scale, qrWidth = 84 * scale;
            float titleSize = 17 * scale, modelSize = 10 * scale;
            List<String> organization = wrap(snapshot.organizationName(), bold, 11 * scale, WIDTH - 2 * padding);
            List<String> title = wrap(snapshot.containerName(), bold, titleSize, WIDTH - qrWidth - 2 * padding);
            List<String> model =
                    wrap("Model Type: " + snapshot.containerModel(), regular, modelSize, WIDTH - qrWidth - 2 * padding);
            List<String> description = new ArrayList<>();
            if (snapshot.unitDescription() != null)
                for (String paragraph : snapshot.unitDescription().split("\n", -1)) {
                    List<String> lines = wrap(paragraph, regular, modelSize, WIDTH - qrWidth - 2 * padding);
                    description.addAll(lines.isEmpty() ? List.of("") : lines);
                }
            float orgHeight = organization.size() * 13 * scale + 2 * padding;
            float identityHeight = Math.max(
                    qr + 18 * scale + 2 * padding,
                    title.size() * 19 * scale
                            + gap
                            + (model.size() + description.size()) * 12 * scale
                            + (description.isEmpty() ? 0 : gap)
                            + 2 * padding);
            float contentsTop = HALF_HEIGHT - MARGIN - orgHeight - identityHeight - 2 * gap;
            float tableWidth = (WIDTH - (columns - 1) * gap) / columns;
            float quantityWidth = tableWidth * .24f,
                    codeWidth =
                            Math.max(tableWidth * .19f, measure(regular, font, snapshot.containerCode()) + 2 * padding);
            float itemWidth = tableWidth - quantityWidth - codeWidth;
            if (itemWidth <= 2 * padding) continue;
            List<List<Row>> tables = new ArrayList<>();
            boolean fits = true;
            for (int column = 0; column < columns; column++) {
                List<Entry> subset = columns == 1
                        ? entries
                        : column == 0 ? entries.subList(0, split) : entries.subList(split, entries.size());
                List<Row> rows = new ArrayList<>();
                boolean nestedHeading = false;
                for (Entry entry : subset) {
                    if (entry.nested() && !nestedHeading) {
                        rows.add(new Row(
                                List.of(),
                                wrap("Nested containers (current)", bold, font, tableWidth - 2 * padding),
                                List.of(),
                                true));
                        nestedHeading = true;
                    }
                    rows.add(new Row(
                            wrap(entry.quantity(), regular, font, quantityWidth - 2 * padding),
                            wrap(entry.item(), regular, font, itemWidth - 2 * padding),
                            wrap(entry.code(), regular, font, codeWidth - 2 * padding),
                            false));
                }
                if (entries.isEmpty())
                    rows.add(new Row(
                            List.of(),
                            wrap("No direct packing requirements.", regular, font, itemWidth - 2 * padding),
                            List.of(),
                            false));
                float height =
                        rows.stream().map(Row::lineCount).reduce(0, Integer::sum) * leading + rows.size() * 2 * scale;
                if (height > contentsTop - 20 * scale - MARGIN) fits = false;
                tables.add(List.copyOf(rows));
            }
            if (fits)
                return new Layout(
                        scale,
                        padding,
                        gap,
                        font,
                        leading,
                        qr,
                        qrWidth,
                        titleSize,
                        modelSize,
                        orgHeight,
                        identityHeight,
                        contentsTop,
                        tableWidth,
                        quantityWidth,
                        itemWidth,
                        codeWidth,
                        organization,
                        title,
                        model,
                        List.copyOf(description),
                        List.copyOf(tables));
        }
        throw new IllegalArgumentException("Packing sheet cannot be fitted.");
    }

    private static void drawHalf(
            PDPageContentStream content,
            PackingSheetSnapshot snapshot,
            Layout l,
            PDType0Font regular,
            PDType0Font bold,
            float base)
            throws IOException {
        content.setStrokingColor(0f, 0f, 0f);
        content.setLineWidth(.8f);
        String color = snapshot.containerColor();
        int red = Integer.parseInt(color.substring(1, 3), 16),
                green = Integer.parseInt(color.substring(3, 5), 16),
                blue = Integer.parseInt(color.substring(5, 7), 16);
        boolean whiteText = 299 * red + 587 * green + 114 * blue < 89250;
        float textColor = whiteText ? 1 : 0;
        float top = base + HALF_HEIGHT - MARGIN;
        filledHeader(content, MARGIN, top - l.orgHeight(), WIDTH, l.orgHeight(), red, green, blue);
        content.setNonStrokingColor(textColor, textColor, textColor);
        drawLines(
                content,
                l.organization(),
                bold,
                11 * l.scale(),
                MARGIN + l.padding(),
                top - l.padding() - 11 * l.scale(),
                13 * l.scale());
        float identityTop = top - l.orgHeight() - l.gap();
        filledHeader(content, MARGIN, identityTop - l.identityHeight(), WIDTH, l.identityHeight(), red, green, blue);
        content.setNonStrokingColor(textColor, textColor, textColor);
        float y = identityTop - l.padding() - l.titleSize();
        drawLines(content, l.title(), bold, l.titleSize(), MARGIN + l.padding(), y, 19 * l.scale());
        y -= l.title().size() * 19 * l.scale() + l.gap() - l.titleSize() + l.modelSize();
        drawLines(content, l.model(), regular, l.modelSize(), MARGIN + l.padding(), y, 12 * l.scale());
        y -= l.model().size() * 12 * l.scale() + l.gap();
        drawLines(content, l.description(), regular, l.modelSize(), MARGIN + l.padding(), y, 12 * l.scale());
        float qrBlockX = MARGIN + WIDTH - l.qrWidth();
        line(content, qrBlockX, identityTop, qrBlockX, identityTop - l.identityHeight());
        float qrX = qrBlockX + (l.qrWidth() - l.qr()) / 2, qrY = identityTop - l.padding() - l.qr();
        drawQr(content, snapshot.containerCode(), qrX, qrY, l.qr());
        content.setNonStrokingColor(textColor, textColor, textColor);
        write(
                content,
                bold,
                11 * l.scale(),
                qrBlockX + (l.qrWidth() - measure(bold, 11 * l.scale(), snapshot.containerCode())) / 2,
                qrY - 14 * l.scale(),
                snapshot.containerCode());
        content.setNonStrokingColor(0f, 0f, 0f);
        for (int column = 0; column < l.tables().size(); column++)
            drawTable(
                    content,
                    l,
                    l.tables().get(column),
                    regular,
                    bold,
                    base,
                    MARGIN + column * (l.tableWidth() + l.gap()));
    }

    private static void filledHeader(
            PDPageContentStream content, float x, float y, float width, float height, int red, int green, int blue)
            throws IOException {
        content.setNonStrokingColor(red / 255f, green / 255f, blue / 255f);
        content.addRect(x, y, width, height);
        content.fillAndStroke();
    }

    private static void drawTable(
            PDPageContentStream content,
            Layout l,
            List<Row> rows,
            PDType0Font regular,
            PDType0Font bold,
            float base,
            float x)
            throws IOException {
        float top = base + l.contentsTop(), itemX = x + l.quantityWidth(), codeX = itemX + l.itemWidth();
        box(content, x, base + MARGIN, l.tableWidth(), top - base - MARGIN);
        line(content, itemX, top, itemX, base + MARGIN);
        line(content, codeX, top, codeX, base + MARGIN);
        write(content, bold, l.font(), x + l.padding(), top - 14 * l.scale(), "Quantity");
        write(content, bold, l.font(), itemX + l.padding(), top - 14 * l.scale(), "Item");
        write(content, bold, l.font(), codeX + l.padding(), top - 14 * l.scale(), "Code");
        float y = top - 20 * l.scale();
        line(content, x, y, x + l.tableWidth(), y);
        for (Row row : rows) {
            float height = row.lineCount() * l.leading() + 2 * l.scale();
            if (row.section()) {
                content.setNonStrokingColor(1f, 1f, 1f);
                content.addRect(x + .4f, y - height + .4f, l.tableWidth() - .8f, height - .8f);
                content.fill();
                content.setNonStrokingColor(0f, 0f, 0f);
                drawLines(content, row.item(), bold, l.font(), x + l.padding(), y - l.scale() - l.font(), l.leading());
            } else {
                drawLines(
                        content,
                        row.quantity(),
                        regular,
                        l.font(),
                        x + l.padding(),
                        y - l.scale() - l.font(),
                        l.leading());
                drawLines(
                        content,
                        row.item(),
                        regular,
                        l.font(),
                        itemX + l.padding(),
                        y - l.scale() - l.font(),
                        l.leading());
                drawLines(
                        content,
                        row.code(),
                        regular,
                        l.font(),
                        codeX + l.padding(),
                        y - l.scale() - l.font(),
                        l.leading());
            }
            y -= height;
            line(content, x, y, x + l.tableWidth(), y);
        }
    }

    private static void box(PDPageContentStream content, float x, float y, float width, float height)
            throws IOException {
        content.addRect(x, y, width, height);
        content.stroke();
    }

    private static void line(PDPageContentStream content, float x, float y, float endX, float endY) throws IOException {
        content.moveTo(x, y);
        content.lineTo(endX, endY);
        content.stroke();
    }

    private static void drawLines(
            PDPageContentStream content,
            List<String> lines,
            PDType0Font font,
            float size,
            float x,
            float y,
            float leading)
            throws IOException {
        for (String text : lines) {
            write(content, font, size, x, y, text);
            y -= leading;
        }
    }

    private static List<String> wrap(String source, PDType0Font font, float size, float width) throws IOException {
        if (source == null || source.isBlank()) return List.of();
        String text = source.replaceAll("\\s+", " ").trim();
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!current.isEmpty() && measure(font, size, current + " " + word) <= width) {
                current.append(' ').append(word);
                continue;
            }
            if (!current.isEmpty()) {
                result.add(current.toString());
                current.setLength(0);
            }
            String remaining = word;
            while (measure(font, size, remaining) > width) {
                int low = 1, high = remaining.length();
                while (low < high) {
                    int candidate = (low + high + 1) / 2;
                    if (measure(font, size, remaining.substring(0, candidate)) <= width) low = candidate;
                    else high = candidate - 1;
                }
                result.add(remaining.substring(0, low));
                remaining = remaining.substring(low);
            }
            current.append(remaining);
        }
        if (!current.isEmpty()) result.add(current.toString());
        return List.copyOf(result);
    }

    private static void drawQr(PDPageContentStream content, String value, float x, float y, float size)
            throws IOException {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 4);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix;
        try {
            matrix = new MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not encode packing-sheet QR code.", exception);
        }
        content.setNonStrokingColor(1f, 1f, 1f);
        content.addRect(x, y, size, size);
        content.fill();
        content.setNonStrokingColor(0f, 0f, 0f);
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

    private static PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        try (InputStream stream = PackingSheetDocument.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Bundled packing-sheet font is missing: " + resource);
            return PDType0Font.load(document, stream, true);
        }
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record Entry(String quantity, String item, String code, boolean nested) {}

    private record Row(List<String> quantity, List<String> item, List<String> code, boolean section) {
        int lineCount() {
            return Math.max(1, Math.max(quantity.size(), Math.max(item.size(), code.size())));
        }
    }

    private record Layout(
            float scale,
            float padding,
            float gap,
            float font,
            float leading,
            float qr,
            float qrWidth,
            float titleSize,
            float modelSize,
            float orgHeight,
            float identityHeight,
            float contentsTop,
            float tableWidth,
            float quantityWidth,
            float itemWidth,
            float codeWidth,
            List<String> organization,
            List<String> title,
            List<String> model,
            List<String> description,
            List<List<Row>> tables) {}
}
