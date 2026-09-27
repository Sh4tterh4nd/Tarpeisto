package io.kellermann.bigcontainers.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
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

    @Test
    void rendersDuplicateA5HalvesAndDecodableCanonicalQr() throws Exception {
        byte[] pdf = PackingSheetDocument.render(snapshot(List.of(new PackingSheetSnapshot.Requirement(
                PackingSheetSnapshot.Kind.SERIALIZED_MODEL,
                "Network cable with a deliberately long unbroken identifier ABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890",
                BigDecimal.TEN,
                null,
                null,
                null))));

        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(document);
            assertThat(occurrences(text, "10 x Network cable")).isEqualTo(2);
            BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 300);
            assertThat(decode(rendered.getSubimage(2140, 140, 300, 300))).isEqualTo("7K3MXY");
            assertThat(decode(rendered.getSubimage(2140, 1890, 300, 300))).isEqualTo("7K3MXY");
            BufferedImage preview = new PDFRenderer(document).renderImageWithDPI(0, 72);
            javax.imageio.ImageIO.write(
                    preview,
                    "png",
                    java.nio.file.Path.of("build/packing-sheet-preview.png").toFile());
            assertThat(rasterDigest(preview))
                    .isEqualTo("74915ff89b9ca18f1b66266eeab466ae8fa679021cea909ae9da6b294d98f7dd");
        }
    }

    @Test
    void paginatesLargeExactListsWithoutOmittingRows() throws Exception {
        List<PackingSheetSnapshot.Requirement> requirements = new ArrayList<>();
        for (int index = 0; index < 150; index++) {
            requirements.add(new PackingSheetSnapshot.Requirement(
                    PackingSheetSnapshot.Kind.EXACT,
                    "Cable",
                    BigDecimal.ONE,
                    null,
                    String.format("A%05d", index),
                    "Exact unit " + index));
        }
        byte[] pdf = PackingSheetDocument.render(snapshot(requirements));
        try (PDDocument document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(document);
            for (int index = 0; index < 150; index++) {
                assertThat(occurrences(text, String.format("A%05d", index))).isEqualTo(2);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"17, 1, 12", "18, 2, 12", "36, 3, 12", "54, 3, 11", "300, 3, 8"})
    void fitsColumnsBeforeReducingFontAndOnlyThenAddsPages(int count, int expectedColumns, int expectedFont)
            throws Exception {
        List<PackingSheetSnapshot.Requirement> requirements = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            requirements.add(new PackingSheetSnapshot.Requirement(
                    PackingSheetSnapshot.Kind.SERIALIZED_MODEL, "Unit" + index, BigDecimal.ONE, null, null, null));
        }
        var snapshot = new PackingSheetSnapshot("Box", "Case", "7K3MXY", "#112233", requirements, List.of());
        try (PDDocument document = Loader.loadPDF(PackingSheetDocument.render(snapshot))) {
            PositionedText text = new PositionedText();
            text.getText(document);
            var body = text.rows.stream()
                    .filter(row -> row.text().startsWith("1 x Unit"))
                    .toList();
            assertThat(body.stream().map(Row::fontSize).distinct()).containsExactly((float) expectedFont);
            assertThat(body.stream().map(Row::x).distinct().count()).isEqualTo(expectedColumns);
            assertThat(document.getNumberOfPages()).isEqualTo(count == 300 ? 5 : 1);
            for (int index = 0; index < count; index++) {
                String expected = "1 x Unit" + index;
                assertThat(body.stream()
                                .filter(row -> row.text().equals(expected))
                                .count())
                        .isEqualTo(2);
            }
        }
    }

    @Test
    void wrapsEntireLongHeaderAndRequirementsWithoutOverlapAndPreservesDuplicateGeometry() throws Exception {
        String title = "Container " + "ABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(9);
        String model = "Model " + "LongModelIdentifier".repeat(12);
        var snapshot = new PackingSheetSnapshot(
                title,
                model,
                "7K3MXY",
                "#B8860B",
                List.of(
                        new PackingSheetSnapshot.Requirement(
                                PackingSheetSnapshot.Kind.CONSUMABLE,
                                "Tape",
                                new BigDecimal("123456.125"),
                                "rolls",
                                null,
                                null),
                        new PackingSheetSnapshot.Requirement(
                                PackingSheetSnapshot.Kind.EXACT,
                                "Gateway",
                                BigDecimal.ONE,
                                null,
                                "91TRQJ",
                                "Configured Gateway")),
                List.of());
        try (PDDocument document = Loader.loadPDF(PackingSheetDocument.render(snapshot))) {
            PositionedText text = new PositionedText();
            text.getText(document);
            float half = PDRectangle.A4.getHeight() / 2;
            var top = text.rows.stream().filter(row -> row.y() < half).toList();
            var bottom = text.rows.stream().filter(row -> row.y() > half).toList();
            assertThat(top).hasSameSizeAs(bottom);
            for (int index = 0; index < top.size(); index++) {
                assertThat(top.get(index).text()).isEqualTo(bottom.get(index).text());
                assertThat(top.get(index).x()).isEqualTo(bottom.get(index).x());
                assertThat(bottom.get(index).y() - top.get(index).y())
                        .isCloseTo(half, org.assertj.core.data.Offset.offset(0.01f));
            }
            assertThat(top.stream()
                            .filter(row -> row.fontSize() == 22)
                            .map(Row::text)
                            .reduce("", String::concat))
                    .isEqualTo(title.replace(" ", ""));
            assertThat(top.stream()
                            .filter(row -> row.fontSize() == 9)
                            .map(Row::text)
                            .reduce("", String::concat))
                    .isEqualTo(model.replace(" ", ""));
            assertThat(text.rows.stream()
                            .filter(row -> row.text().equals("123456.125 rolls Tape"))
                            .count())
                    .isEqualTo(2);
            assertThat(text.rows.stream()
                            .filter(row -> row.text().replaceAll("\\s+", " ").equals("91TRQJ Configured Gateway"))
                            .count())
                    .isEqualTo(2);
            assertThat(top.stream()
                            .filter(row -> row.text().equals("Packing requirements"))
                            .findFirst()
                            .orElseThrow()
                            .y())
                    .isGreaterThan(top.stream()
                                    .filter(row -> row.text().equals("7K3MXY"))
                                    .map(Row::y)
                                    .max(Float::compare)
                                    .orElseThrow()
                            + 12);
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 72);
            int black = 0;
            for (int y = 2; y < 18; y++) {
                for (int x = 20; x < 300; x++) {
                    if ((image.getRGB(x, y) & 0xFFFFFF) == 0) black++;
                }
            }
            assertThat(black).isPositive();
        }
    }

    private record Row(String text, float x, float y, float fontSize) {}

    private static final class PositionedText extends PDFTextStripper {
        private final List<Row> rows = new ArrayList<>();

        @Override
        protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
            TextPosition first = positions.getFirst();
            rows.add(new Row(text, first.getXDirAdj(), first.getYDirAdj(), first.getFontSizeInPt()));
            for (TextPosition position : positions) {
                assertThat(position.getXDirAdj() + position.getWidthDirAdj())
                        .isLessThanOrEqualTo(PDRectangle.A4.getWidth() - 19.9f);
                assertThat(position.getYDirAdj()).isBetween(0f, PDRectangle.A4.getHeight() - 18f);
            }
            super.writeString(text, positions);
        }
    }

    private static PackingSheetSnapshot snapshot(List<PackingSheetSnapshot.Requirement> requirements) {
        return new PackingSheetSnapshot(
                "Mobile Network Box",
                "RAKO 400 x 300",
                "7K3MXY",
                "#112233",
                requirements,
                List.of(new PackingSheetSnapshot.ChildContainer("Inner cable case", "Pelican", "91TRQJ")));
    }

    private static String decode(BufferedImage image) throws Exception {
        return new MultiFormatReader()
                .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image))))
                .getText();
    }

    private static String rasterDigest(BufferedImage image) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                digest.update((byte) (rgb >> 16));
                digest.update((byte) (rgb >> 8));
                digest.update((byte) rgb);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}
