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
import org.junit.jupiter.params.provider.CsvSource;

class PackingSheetDocumentTests {
    private static final float HALF = PDRectangle.A4.getHeight() / 2;
    private static final float ITEM_X = 138;
    private static final float CODE_X = PDRectangle.A4.getWidth() - 98;

    @Test
    void borderedSemanticColumnsDuplicateAndBothCanonicalQrCodesDecode() throws Exception {
        var snapshot = mixed();
        byte[] pdf = PackingSheetDocument.render(snapshot);
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isOne();
            var positioned = positions(document);
            String text = new PDFTextStripper().getText(document);
            assertThat(occurrences(text, "Community Equipment Team")).isEqualTo(2);
            assertThat(occurrences(text, "Model Type: RAKO 400 x 300")).isEqualTo(2);
            assertThat(occurrences(text, "Nested containers (current)")).isEqualTo(2);
            assertThat(occurrences(text, "2.125 rolls")).isEqualTo(2);
            assertThat(positioned.runs.stream().filter(run -> run.text().equals("10")))
                    .allSatisfy(run -> assertThat(run.x()).isEqualTo(28));
            assertThat(positioned.runs.stream().filter(run -> run.text().equals("Configured Gateway")))
                    .allSatisfy(run -> assertThat(run.x()).isEqualTo(ITEM_X));
            assertThat(positioned.runs.stream().filter(run -> run.text().equals("91TRQJ")))
                    .hasSize(2)
                    .allSatisfy(code -> {
                        assertThat(code.x()).isCloseTo(CODE_X, offset(0.01f));
                        assertThat(positioned.runs).anySatisfy(quantity -> {
                            assertThat(quantity.text()).isEqualTo("1");
                            assertThat(quantity.x()).isEqualTo(28);
                            assertThat(quantity.y()).isEqualTo(code.y());
                        });
                    });
            assertDuplicatedGeometry(positioned);
            var image = new PDFRenderer(document).renderImageWithDPI(0, 300);
            assertThat(decode(image.getSubimage(image.getWidth() - 400, 0, 390, 900)))
                    .isEqualTo("7K3MXY");
            assertThat(decode(image.getSubimage(image.getWidth() - 400, image.getHeight() / 2, 390, 900)))
                    .isEqualTo("7K3MXY");
            var preview = new PDFRenderer(document).renderImageWithDPI(0, 72);
            for (int base : List.of(0, Math.round(HALF))) {
                assertBorder(preview, 20, base + 20);
                assertBorder(preview, 20, base + Math.round(HALF) - 20);
                assertThat(preview.getRGB(450, base + 28) & 0xffffff).isEqualTo(0xffffff);
            }
            // Approved after inspecting the mixed and continuation-page samples.
            assertThat(rasterDigest(preview))
                    .isEqualTo("25fea4ce0cf4254f74f76ab9a7ca28cb1388116962186033a627affbd88dc208");
            sample("mixed", pdf, document);
        }
    }

    @Test
    void emptySheetKeepsFullHeightContentsBorderAndTableHeader() throws Exception {
        byte[] pdf = PackingSheetDocument.render(snapshot(List.of(), List.of()));
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(occurrences(text, "No direct packing requirements.")).isEqualTo(2);
            for (String header : List.of("Quantity", "Item", "Code"))
                assertThat(occurrences(text, header)).isEqualTo(2);
            assertDuplicatedGeometry(positions(document));
            sample("empty", pdf, document);
        }
    }

    @ParameterizedTest
    @CsvSource({"2,12", "11,9", "12,8", "150,8"})
    void keepsOneSemanticTableShrinksOnlyWhenNeededAndPaginatesAtomicRows(int count, int expectedSize)
            throws Exception {
        List<PackingSheetSnapshot.Requirement> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(exact(String.format("A%05d", i), "Exact unit " + i));
        byte[] pdf = PackingSheetDocument.render(snapshot(rows, List.of()));
        try (var document = Loader.loadPDF(pdf)) {
            var positioned = positions(document);
            for (int i = 0; i < count; i++) {
                String code = String.format("A%05d", i), name = "Exact unit " + i;
                var codes = positioned.runs.stream()
                        .filter(run -> run.text().equals(code))
                        .toList();
                assertThat(codes).hasSize(2);
                for (var run : codes) {
                    assertThat(run.fontSize()).isEqualTo(expectedSize);
                    assertThat(run.x()).isCloseTo(CODE_X, offset(0.01f));
                    assertThat(positioned.runs).anySatisfy(item -> {
                        assertThat(item.text()).isEqualTo(name);
                        assertThat(item.page()).isEqualTo(run.page());
                        assertThat(item.y()).isEqualTo(run.y());
                        assertThat(item.x()).isEqualTo(ITEM_X);
                    });
                }
            }
            assertDuplicatedGeometry(positioned);
            if (count == 150) {
                assertThat(document.getNumberOfPages()).isGreaterThan(1);
                sample("overflow", pdf, document);
            }
        }
    }

    @Test
    void independentlyWrapsLongNamesWordsQuantitiesAndCompleteHeaderWithoutCellOverlap() throws Exception {
        String name = "Container " + "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(9);
        String model = "LongModelIdentifier".repeat(12);
        var snapshot = new PackingSheetSnapshot(
                name,
                model,
                "7K3MXY",
                "Long Organization " + "Community ".repeat(8),
                List.of(
                        new PackingSheetSnapshot.Requirement(
                                PackingSheetSnapshot.Kind.CONSUMABLE,
                                "Gaffer tape with an exceptionally long unbroken model identifier " + "Z".repeat(140),
                                new BigDecimal("1234567890123.125"),
                                "special-purpose rolls",
                                null,
                                null),
                        exact("91TRQJ", "Configured " + "GatewayIdentifier".repeat(22))),
                List.of());
        byte[] pdf = PackingSheetDocument.render(snapshot);
        try (var document = Loader.loadPDF(pdf)) {
            var positioned = positions(document);
            assertDuplicatedGeometry(positioned);
            String topTitle = positioned.runs.stream()
                    .filter(run -> run.page() == 1
                            && run.y() < HALF
                            && run.fontSize() >= 10
                            && run.x() == 28
                            && !run.text().startsWith("Long Organization")
                            && !run.text().startsWith("Community")
                            && !run.text().startsWith("Model Type:")
                            && !run.text().startsWith("LongModelIdentifier")
                            && !run.text().equals("Quantity"))
                    .map(Run::text)
                    .reduce("", String::concat);
            assertThat(topTitle.replace(" ", "")).contains(name.replace(" ", ""));
            String text = new PDFTextStripper().getText(document);
            assertThat(text).doesNotContain("...");
            assertThat(occurrences(text, "91TRQJ")).isEqualTo(2);
            assertThat(positioned.runs.stream()
                            .filter(run -> run.x() == ITEM_X)
                            .map(Run::text)
                            .reduce("", String::concat))
                    .contains("Gaffer", "Configured");
            sample("long", pdf, document);
        }
    }

    @Test
    void exceptionalSingleRowContinuesWithoutRepeatingQuantityOrCodeOrDroppingText() throws Exception {
        String giant = "G".repeat(5000) + "FINISH";
        byte[] pdf = PackingSheetDocument.render(snapshot(List.of(exact("91TRQJ", giant)), List.of()));
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            var positioned = positions(document);
            assertDuplicatedGeometry(positioned);
            assertThat(positioned.runs.stream().filter(run -> run.text().equals("91TRQJ")))
                    .hasSize(2);
            assertThat(positioned.runs.stream().filter(run -> run.text().equals("1")))
                    .hasSize(2);
            String top = positioned.runs.stream()
                    .filter(run ->
                            run.x() == ITEM_X && run.y() < HALF && !run.text().equals("Item"))
                    .map(Run::text)
                    .reduce("", String::concat);
            assertThat(top).isEqualTo(giant);
            sample("giant", pdf, document);
        }
    }

    @ParameterizedTest
    @CsvSource({"130", "600"})
    void nestedHeadingStaysWithFirstFragmentOfExceptionallyTallChild(int repeats) throws Exception {
        String giant = "NestedChild".repeat(repeats);
        byte[] pdf = PackingSheetDocument.render(
                snapshot(List.of(), List.of(new PackingSheetSnapshot.ChildContainer(giant, "Model", "91TRQJ"))));
        try (var document = Loader.loadPDF(pdf)) {
            var positioned = positions(document);
            assertDuplicatedGeometry(positioned);
            var first = positioned.runs.stream()
                    .filter(run -> run.page() == 1 && run.y() < HALF)
                    .toList();
            assertThat(first).anyMatch(run -> run.text().equals("Nested containers (current)"));
            assertThat(first).anyMatch(run -> run.text().equals("91TRQJ"));
            assertThat(first).anyMatch(run -> run.x() == ITEM_X && run.text().startsWith("NestedChild"));
            String rendered = positioned.runs.stream()
                    .filter(run ->
                            run.x() == ITEM_X && run.y() < HALF && !run.text().equals("Item"))
                    .map(Run::text)
                    .reduce("", String::concat);
            assertThat(rendered).isEqualTo(giant);
            assertThat(positioned.runs.stream().filter(run -> run.text().equals("91TRQJ")))
                    .hasSize(2);
            sample(repeats == 600 ? "giant-child" : "tall-child", pdf, document);
        }
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
            else runs.add(new Run(text.getUnicode(), x, y, text.getFontSizeInPt(), getCurrentPageNo()));
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

    private static void assertBorder(BufferedImage image, int x, int y) {
        boolean dark = false;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++) dark |= (image.getRGB(x + dx, y + dy) & 0xffffff) < 0xeeeeee;
        assertThat(dark).isTrue();
    }

    private static String decode(BufferedImage image) throws Exception {
        return new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
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
