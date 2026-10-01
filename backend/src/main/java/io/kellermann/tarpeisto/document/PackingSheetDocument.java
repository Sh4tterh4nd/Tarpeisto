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

/** Portrait A4 pages contain identical, bordered landscape A5 tables on both halves. */
public final class PackingSheetDocument {
    private static final String REGULAR_FONT = "/document-fonts/RobotoMono-Regular.ttf";
    private static final String BOLD_FONT = "/document-fonts/RobotoMono-Bold.ttf";
    private static final float HALF_HEIGHT = PDRectangle.A4.getHeight() / 2;
    private static final float MARGIN = 20;
    private static final float WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float QUANTITY_WIDTH = 110;
    private static final float CODE_WIDTH = 86;
    private static final float ITEM_WIDTH = WIDTH - QUANTITY_WIDTH - CODE_WIDTH;
    private static final float QR_SIZE = 60;
    private static final float QR_BLOCK_WIDTH = 90;
    private static final float GAP = 6;
    private static final float PADDING = 8;
    private static final float ROW_PADDING = 4;
    private static final float TABLE_HEADER_HEIGHT = 25;

    private PackingSheetDocument() {}

    public static byte[] render(PackingSheetSnapshot snapshot) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font regular = loadFont(document, REGULAR_FONT);
            PDType0Font bold = loadFont(document, BOLD_FONT);
            Header header = header(snapshot, regular, bold);
            Layout layout = chooseLayout(snapshot, regular, bold, header);
            List<List<Row>> pages = paginate(rows(snapshot, layout, regular, bold), layout);
            for (List<Row> rows : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    drawHalf(content, snapshot, header, rows, layout, regular, bold, HALF_HEIGHT);
                    drawHalf(content, snapshot, header, rows, layout, regular, bold, 0);
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the container packing-sheet PDF.", exception);
        }
    }

    private static Layout chooseLayout(
            PackingSheetSnapshot snapshot, PDType0Font regular, PDType0Font bold, Header header) throws IOException {
        for (int size = 12; size >= 8; size--) {
            Layout layout = new Layout(size, header.contentsTop());
            float height = 0;
            for (Row row : rows(snapshot, layout, regular, bold)) height += row.height(layout);
            if (height <= layout.capacity()) return layout;
        }
        return new Layout(8, header.contentsTop());
    }

    private static List<Row> rows(PackingSheetSnapshot snapshot, Layout layout, PDType0Font regular, PDType0Font bold)
            throws IOException {
        List<Row> rows = new ArrayList<>();
        for (var requirement : snapshot.requirements()) {
            boolean exact = requirement.kind() == PackingSheetSnapshot.Kind.EXACT;
            String quantity = exact ? "1" : number(requirement.quantity());
            if (requirement.kind() == PackingSheetSnapshot.Kind.CONSUMABLE) quantity += " " + requirement.stockUnit();
            rows.add(row(
                    quantity,
                    exact ? requirement.exactAssetName() : requirement.modelName(),
                    exact ? requirement.exactAssetCode() : "",
                    layout,
                    regular));
        }
        if (!snapshot.childContainers().isEmpty()) {
            rows.add(new Row(
                    List.of(),
                    wrap("Nested containers (current)", bold, layout.fontSize(), WIDTH - 2 * PADDING),
                    List.of(),
                    true));
            for (var child : snapshot.childContainers())
                rows.add(row("1", child.name(), child.publicCode(), layout, regular));
        }
        if (rows.isEmpty()) rows.add(row("", "No direct packing requirements.", "", layout, regular));
        return rows;
    }

    private static Row row(String quantity, String item, String code, Layout layout, PDType0Font font)
            throws IOException {
        return new Row(
                wrap(quantity, font, layout.fontSize(), QUANTITY_WIDTH - 2 * PADDING),
                wrap(item, font, layout.fontSize(), ITEM_WIDTH - 2 * PADDING),
                wrap(code, font, layout.fontSize(), CODE_WIDTH - 2 * PADDING),
                false);
    }

    private static List<List<Row>> paginate(List<Row> rows, Layout layout) {
        List<List<Row>> pages = new ArrayList<>();
        List<Row> current = new ArrayList<>();
        float used = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            float height = row.height(layout);
            float keepTogether = height;
            if (row.section() && index + 1 < rows.size())
                keepTogether += Math.min(rows.get(index + 1).height(layout), layout.capacity() - height);
            boolean followsSection = !current.isEmpty() && current.getLast().section();
            if (!current.isEmpty() && used + keepTogether > layout.capacity() && !followsSection) {
                pages.add(List.copyOf(current));
                current.clear();
                used = 0;
            }
            if (used + height <= layout.capacity()) {
                current.add(row);
                used += height;
                continue;
            }
            // Exceptionally tall rows also reserve their preceding section heading. Cell lines use the
            // same vertical offset, so short Quantity/Code values occur only in its first fragment.
            int offset = 0;
            while (offset < row.lineCount()) {
                int linesAvailable =
                        (int) Math.floor((layout.capacity() - used - 2 * ROW_PADDING) / layout.lineHeight());
                if (linesAvailable < 1) {
                    pages.add(List.copyOf(current));
                    current.clear();
                    used = 0;
                    continue;
                }
                int end = Math.min(row.lineCount(), offset + linesAvailable);
                Row part = row.slice(offset, end);
                current.add(part);
                used += part.height(layout);
                offset = end;
                if (offset < row.lineCount()) {
                    pages.add(List.copyOf(current));
                    current.clear();
                    used = 0;
                }
            }
        }
        if (!current.isEmpty()) pages.add(List.copyOf(current));
        return List.copyOf(pages);
    }

    private static Header header(PackingSheetSnapshot snapshot, PDType0Font regular, PDType0Font bold)
            throws IOException {
        List<String> organization = wrap(snapshot.organizationName(), bold, 12, WIDTH - 2 * PADDING);
        float organizationHeight = Math.max(26, organization.size() * 14 + 2 * PADDING);
        for (int titleSize = 18; titleSize >= 10; titleSize--) {
            List<String> title = wrap(snapshot.containerName(), bold, titleSize, WIDTH - QR_BLOCK_WIDTH - 2 * PADDING);
            List<String> model =
                    wrap("Model Type: " + snapshot.containerModel(), regular, 10, WIDTH - QR_BLOCK_WIDTH - 2 * PADDING);
            float identityHeight = Math.max(92, title.size() * (titleSize + 2) + GAP + model.size() * 12 + 2 * PADDING);
            float contentsTop = HALF_HEIGHT - MARGIN - organizationHeight - GAP - identityHeight - GAP;
            // Reserve at least a section heading and one minimum-size body line.
            if (contentsTop - TABLE_HEADER_HEIGHT - MARGIN >= 2 * (8 + 3 + 2 * ROW_PADDING))
                return new Header(
                        organization, organizationHeight, title, titleSize, model, identityHeight, contentsTop);
        }
        throw new IllegalArgumentException("Container identity is too long for an A5 packing-sheet header.");
    }

    private static void drawHalf(
            PDPageContentStream content,
            PackingSheetSnapshot snapshot,
            Header header,
            List<Row> rows,
            Layout layout,
            PDType0Font regular,
            PDType0Font bold,
            float base)
            throws IOException {
        content.setStrokingColor(0f, 0f, 0f);
        content.setNonStrokingColor(0f, 0f, 0f);
        content.setLineWidth(0.8f);
        float top = base + HALF_HEIGHT - MARGIN;
        box(content, MARGIN, top - header.organizationHeight(), WIDTH, header.organizationHeight());
        drawLines(content, header.organization(), bold, 12, MARGIN + PADDING, top - PADDING - 12, 14);
        float identityTop = top - header.organizationHeight() - GAP;
        box(content, MARGIN, identityTop - header.identityHeight(), WIDTH, header.identityHeight());
        drawLines(
                content,
                header.title(),
                bold,
                header.titleSize(),
                MARGIN + PADDING,
                identityTop - PADDING - header.titleSize(),
                header.titleSize() + 2);
        drawLines(
                content,
                header.model(),
                regular,
                10,
                MARGIN + PADDING,
                identityTop - PADDING - header.title().size() * (header.titleSize() + 2) - GAP - 10,
                12);
        float qrBlockX = MARGIN + WIDTH - QR_BLOCK_WIDTH;
        line(content, qrBlockX, identityTop, qrBlockX, identityTop - header.identityHeight());
        float qrX = qrBlockX + (QR_BLOCK_WIDTH - QR_SIZE) / 2;
        float qrY = identityTop - PADDING - QR_SIZE;
        drawQr(content, snapshot.containerCode(), qrX, qrY, QR_SIZE);
        write(
                content,
                bold,
                11,
                qrBlockX + (QR_BLOCK_WIDTH - measure(bold, 11, snapshot.containerCode())) / 2,
                qrY - 16,
                snapshot.containerCode());

        float tableTop = base + layout.contentsTop();
        box(content, MARGIN, base + MARGIN, WIDTH, tableTop - base - MARGIN);
        float itemX = MARGIN + QUANTITY_WIDTH, codeX = itemX + ITEM_WIDTH;
        line(content, itemX, tableTop, itemX, base + MARGIN);
        line(content, codeX, tableTop, codeX, base + MARGIN);
        write(content, bold, 11, MARGIN + PADDING, tableTop - 17, "Quantity");
        write(content, bold, 11, itemX + PADDING, tableTop - 17, "Item");
        write(content, bold, 11, codeX + PADDING, tableTop - 17, "Code");
        float y = tableTop - TABLE_HEADER_HEIGHT;
        line(content, MARGIN, y, MARGIN + WIDTH, y);
        for (Row row : rows) {
            if (row.section()) {
                // A section label spans the table and remains clearly separate from quantities.
                content.setNonStrokingColor(1f, 1f, 1f);
                content.addRect(MARGIN + 0.4f, y - row.height(layout) + 0.4f, WIDTH - 0.8f, row.height(layout) - 0.8f);
                content.fill();
                content.setNonStrokingColor(0f, 0f, 0f);
                drawLines(
                        content,
                        row.item(),
                        bold,
                        layout.fontSize(),
                        MARGIN + PADDING,
                        y - ROW_PADDING - layout.fontSize(),
                        layout.lineHeight());
            } else {
                drawLines(
                        content,
                        row.quantity(),
                        regular,
                        layout.fontSize(),
                        MARGIN + PADDING,
                        y - ROW_PADDING - layout.fontSize(),
                        layout.lineHeight());
                drawLines(
                        content,
                        row.item(),
                        regular,
                        layout.fontSize(),
                        itemX + PADDING,
                        y - ROW_PADDING - layout.fontSize(),
                        layout.lineHeight());
                drawLines(
                        content,
                        row.code(),
                        regular,
                        layout.fontSize(),
                        codeX + PADDING,
                        y - ROW_PADDING - layout.fontSize(),
                        layout.lineHeight());
            }
            y -= row.height(layout);
            line(content, MARGIN, y, MARGIN + WIDTH, y);
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

    private static PDType0Font loadFont(PDDocument document, String resource) throws IOException {
        try (InputStream stream = PackingSheetDocument.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Bundled packing-sheet font is missing: " + resource);
            return PDType0Font.load(document, stream, true);
        }
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private record Header(
            List<String> organization,
            float organizationHeight,
            List<String> title,
            int titleSize,
            List<String> model,
            float identityHeight,
            float contentsTop) {}

    private record Layout(int fontSize, float contentsTop) {
        float lineHeight() {
            return fontSize + 3;
        }

        float capacity() {
            return contentsTop - TABLE_HEADER_HEIGHT - MARGIN;
        }
    }

    private record Row(List<String> quantity, List<String> item, List<String> code, boolean section) {
        int lineCount() {
            return Math.max(1, Math.max(quantity.size(), Math.max(item.size(), code.size())));
        }

        float height(Layout layout) {
            return lineCount() * layout.lineHeight() + 2 * ROW_PADDING;
        }

        Row slice(int from, int to) {
            return new Row(slice(quantity, from, to), slice(item, from, to), slice(code, from, to), section);
        }

        private static List<String> slice(List<String> lines, int from, int to) {
            return lines.subList(Math.min(from, lines.size()), Math.min(to, lines.size()));
        }
    }
}
