package io.kellermann.tarpeisto.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackingSheetDocumentTests {
    private static final float HALF = PDRectangle.A4.getHeight() / 2;

    @Test
    void semanticCellsDuplicateAndBothCanonicalQrCodesDecode() throws Exception {
        byte[] pdf = PackingSheetDocument.render(mixed());
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isOne();
            var positioned = positions(document);
            String text = new PDFTextStripper().getText(document);
            for (String value : List.of(
                    "Community Equipment Team",
                    "Model Type: RAKO 400 x 300",
                    "Nested containers (current)",
                    "2.125 rolls",
                    "91TRQJ")) assertThat(occurrences(text, value)).isEqualTo(2);
            var quantity =
                    positioned.runs.stream().filter(r -> r.text().equals("10")).toList();
            var item = positioned.runs.stream()
                    .filter(r -> r.text().equals("Configured Gateway"))
                    .toList();
            var code = positioned.runs.stream()
                    .filter(r -> r.text().equals("91TRQJ"))
                    .toList();
            assertThat(quantity).hasSize(2);
            assertThat(item).hasSize(2);
            assertThat(code).hasSize(2);
            assertThat(quantity.getFirst().x()).isLessThan(item.getFirst().x());
            assertThat(item.getFirst().x()).isLessThan(code.getFirst().x());
            for (var c : code)
                assertThat(positioned.runs).anySatisfy(q -> {
                    assertThat(q.text()).isEqualTo("1");
                    assertThat(q.y()).isEqualTo(c.y());
                    assertThat(q.x()).isEqualTo(quantity.getFirst().x());
                });
            assertDuplicatedGeometry(positioned);
            assertQr(document);
            sample("mixed", pdf, document);
            assertThat(rasterDigest(new PDFRenderer(document).renderImageWithDPI(0, 72)))
                    .isEqualTo("927bbca814049899e1cb30efccef5b64903e29180c401ce253f6c7878993c6d8");
            // Approved after the root visually reviewed the boundary, color and long-text samples.
            Files.writeString(
                    Path.of("build/packing-sheet-mixed.sha256"),
                    rasterDigest(new PDFRenderer(document).renderImageWithDPI(0, 72)));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, 16, 30, 31, 60, 120})
    void boundaryCountsUseOneOrTwoCompleteTablesOnOneDuplicatedPage(int count) throws Exception {
        List<PackingSheetSnapshot.Requirement> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(exact(String.format("A%05d", i), "Unit " + i));
        byte[] pdf = PackingSheetDocument.render(snapshot(entries, List.of()));
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isOne();
            String text = new PDFTextStripper().getText(document);
            for (String heading : List.of("Quantity", "Item", "Code"))
                assertThat(occurrences(text, heading)).isEqualTo(count > 15 ? 4 : 2);
            for (int i = 0; i < count; i++)
                assertThat(occurrences(text, String.format("A%05d", i))).isEqualTo(2);
            var positioned = positions(document);
            assertDuplicatedGeometry(positioned);
            sample("count-" + count, pdf, document);
            assertQr(document);
            if (count > 30)
                assertThat(positioned.runs.stream()
                                .filter(r -> r.text().startsWith("A0"))
                                .map(Run::fontSize))
                        .allMatch(size -> size < 11);
            sample("count-" + count, pdf, document);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"#FFFFFF", "#112233", "#0078A5", "#0078A4"})
    void coloredHeadersKeepBlackQrOnWhiteQuietZoneAndStrictThreshold(String color) throws Exception {
        var base = mixed();
        var colored = new PackingSheetSnapshot(
                base.containerName(),
                base.containerModel(),
                base.containerCode(),
                base.organizationName(),
                color,
                "Cable service\nKeep the labels visible",
                base.requirements(),
                base.childContainers());
        byte[] pdf = PackingSheetDocument.render(colored);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(new PDFTextStripper().getText(document)).contains("Cable service", "Keep the labels visible");
            assertDuplicatedGeometry(positions(document));
            assertQr(document);
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 72);
            assertThat(image.getRGB(22, 22) & 0xffffff).isEqualTo(Integer.parseInt(color.substring(1), 16));
            // Header text operators encode exactly white below the threshold, black at equality.
            String operators = new String(
                    document.getPage(0).getContents().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
            boolean white = Integer.parseInt(color.substring(1, 3), 16) * 299
                            + Integer.parseInt(color.substring(3, 5), 16) * 587
                            + Integer.parseInt(color.substring(5, 7), 16) * 114
                    < 89250;
            assertThat(operators).contains((white ? "1 1 1" : "0 0 0") + " rg\nBT");
            sample("color-" + color.substring(1), pdf, document);
        }
    }

    @Test
    void longDescriptionNamesWordsAndNestedRowsAllFitWithoutContinuationOrTruncation() throws Exception {
        String giant = "NestedChild".repeat(600) + "FINISH";
        var base = mixed();
        var longSheet = new PackingSheetSnapshot(
                "Container " + "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(9),
                "Model".repeat(55),
                "7K3MXY",
                "Organization " + "Community ".repeat(8),
                "#112233",
                "Description " + "Meaningful text ".repeat(30),
                base.requirements(),
                List.of(new PackingSheetSnapshot.ChildContainer(giant, "Case", "A72KQ5")));
        byte[] pdf = PackingSheetDocument.render(longSheet);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isOne();
            var positioned = positions(document);
            assertDuplicatedGeometry(positioned);
            String compact = positioned.runs.stream()
                    .filter(r -> r.y() < HALF)
                    .map(Run::text)
                    .reduce("", String::concat)
                    .replace(" ", "");
            assertThat(compact)
                    .contains(
                            giant,
                            longSheet.containerName().replace(" ", ""),
                            longSheet.unitDescription().replace(" ", ""));
            assertThat(new PDFTextStripper().getText(document)).doesNotContain("...");
            sample("long", pdf, document);
        }
    }

    @Test
    void readsFirstFifteenDownLeftThenRemainderRightAndRepeatsNestedGuidance() throws Exception {
        List<PackingSheetSnapshot.Requirement> required = new ArrayList<>();
        for (int i = 0; i < 14; i++) required.add(exact(String.format("A%05d", i), "Unit " + i));
        List<PackingSheetSnapshot.ChildContainer> children = List.of(
                new PackingSheetSnapshot.ChildContainer("First nested", "Case", "91TRQJ"),
                new PackingSheetSnapshot.ChildContainer("Second nested", "Case", "A72KQ5"),
                new PackingSheetSnapshot.ChildContainer("Third nested", "Case", "M39TX1"));
        try (var document = Loader.loadPDF(PackingSheetDocument.render(snapshot(required, children)))) {
            var positioned = positions(document);
            var top = positioned.runs.stream().filter(r -> r.y() < HALF).toList();
            var left = top.stream()
                    .filter(r -> r.text().equals("91TRQJ"))
                    .findFirst()
                    .orElseThrow();
            var right = top.stream()
                    .filter(r -> r.text().equals("A72KQ5"))
                    .findFirst()
                    .orElseThrow();
            assertThat(left.x()).isLessThan(PDRectangle.A4.getWidth() / 2);
            assertThat(right.x()).isGreaterThan(PDRectangle.A4.getWidth() / 2);
            assertThat(top.stream().filter(r -> r.text().startsWith("Nested containers")))
                    .hasSize(2);
            assertThat(left.y()).isGreaterThan(right.y());
            assertDuplicatedGeometry(positioned);
        }
    }

    private static void assertQr(PDDocument document) throws Exception {
        BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 300);
        float scale = positions(document).runs.stream()
                        .filter(r -> r.text().equals("7K3MXY") && r.y() < HALF)
                        .findFirst()
                        .orElseThrow()
                        .fontSize()
                / 11;
        float factor = 300f / 72;
        int x = Math.round((PDRectangle.A4.getWidth() - 20 - 79 * scale) * factor);
        int y = Math.round((20 + 28 * scale) * factor);
        int size = Math.round(74 * scale * factor);
        assertThat(decode(image.getSubimage(x, y, size, size))).isEqualTo("7K3MXY");
        assertThat(decode(image.getSubimage(x, y + image.getHeight() / 2, size, size)))
                .isEqualTo("7K3MXY");
    }

    private static PackingSheetSnapshot mixed() {
        return snapshot(
                List.of(
                        new PackingSheetSnapshot.Requirement(
                                PackingSheetSnapshot.Kind.SERIALIZED_MODEL,
                                "LAN 20m",
                                BigDecimal.TEN,
                                null,
                                null,
                                null),
                        new PackingSheetSnapshot.Requirement(
                                PackingSheetSnapshot.Kind.CONSUMABLE,
                                "Gaffer tape 50 mm",
                                new BigDecimal("2.125"),
                                "rolls",
                                null,
                                null),
                        exact("91TRQJ", "Configured Gateway")),
                List.of(new PackingSheetSnapshot.ChildContainer("Inner cable case", "Pelican", "A72KQ5")));
    }

    private static PackingSheetSnapshot snapshot(
            List<PackingSheetSnapshot.Requirement> rows, List<PackingSheetSnapshot.ChildContainer> children) {
        return new PackingSheetSnapshot(
                "Mobile Network Box", "RAKO 400 x 300", "7K3MXY", "Community Equipment Team", rows, children);
    }

    private static PackingSheetSnapshot.Requirement exact(String code, String name) {
        return new PackingSheetSnapshot.Requirement(
                PackingSheetSnapshot.Kind.EXACT, "Gateway", BigDecimal.ONE, null, code, name);
    }

    private static void sample(String name, byte[] pdf, PDDocument document) throws Exception {
        Files.createDirectories(Path.of("build"));
        Files.write(Path.of("build/packing-sheet-" + name + ".pdf"), pdf);
        for (int i = 0; i < document.getNumberOfPages(); i++)
            ImageIO.write(
                    new PDFRenderer(document).renderImageWithDPI(i, 144),
                    "png",
                    Path.of("build/packing-sheet-" + name + (i == 0 ? "" : "-" + (i + 1)) + ".png")
                            .toFile());
    }

    private static Positions positions(PDDocument document) throws Exception {
        var positions = new Positions();
        positions.getText(document);
        return positions;
    }

    private record Run(String text, float x, float y, float fontSize, int page) {}

    private static final class Positions extends PDFTextStripper {
        private final List<Run> runs = new ArrayList<>();
        private float endX;

        @Override
        protected void processTextPosition(TextPosition text) {
            float x = text.getXDirAdj(), y = text.getYDirAdj();
            assertThat(x + text.getWidthDirAdj()).isLessThanOrEqualTo(PDRectangle.A4.getWidth() - 19.5f);
            assertThat(y % HALF).isBetween(20f, HALF - 20f);
            var previous = runs.isEmpty() ? null : runs.getLast();
            if (previous != null
                    && previous.page() == getCurrentPageNo()
                    && Math.abs(previous.y() - y) < 0.01
                    && Math.abs(endX - x) < 0.1)
                runs.set(
                        runs.size() - 1,
                        new Run(
                                previous.text() + text.getUnicode(),
                                previous.x(),
                                previous.y(),
                                previous.fontSize(),
                                previous.page()));
            else runs.add(new Run(text.getUnicode(), x, y, text.getFontSize(), getCurrentPageNo()));
            endX = x + text.getWidthDirAdj();
            super.processTextPosition(text);
        }
    }

    private static void assertDuplicatedGeometry(Positions positions) {
        for (int page : positions.runs.stream().map(Run::page).distinct().toList()) {
            var top = positions.runs.stream()
                    .filter(run -> run.page() == page && run.y() < HALF)
                    .toList();
            var bottom = positions.runs.stream()
                    .filter(run -> run.page() == page && run.y() > HALF)
                    .toList();
            assertThat(top).hasSameSizeAs(bottom);
            for (int i = 0; i < top.size(); i++) {
                assertThat(top.get(i).text()).isEqualTo(bottom.get(i).text());
                assertThat(top.get(i).x()).isEqualTo(bottom.get(i).x());
                assertThat(bottom.get(i).y() - top.get(i).y()).isCloseTo(HALF, offset(0.02f));
            }
        }
    }

    private static String decode(BufferedImage image) throws Exception {
        return new MultiFormatReader()
                .decode(
                        new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))),
                        java.util.Map.of(com.google.zxing.DecodeHintType.TRY_HARDER, true))
                .getText();
    }

    private static String rasterDigest(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                digest.update((byte) (rgb >> 16));
                digest.update((byte) (rgb >> 8));
                digest.update((byte) rgb);
            }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int occurrences(String source, String needle) {
        return source.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
