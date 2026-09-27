package io.kellermann.tarpeisto.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.awt.image.BufferedImage;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

class AssetLabelDocumentTests {

    @Test
    void producesA4PagesWithVectorQrThatRoundTripsThroughARealDecoder() throws Exception {
        byte[] bytes = AssetLabelDocument.labels(
                List.of(new AssetLabelEntry(
                        "7K3MXY", "Network Access Point", "AP, rack one", 1, "Networking", "#112233")),
                AssetLabelFormat.A4_70X36_24,
                0,
                null);

        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isCloseTo(595.2756f, within(0.1f));
            assertThat(document.getPage(0).getMediaBox().getHeight()).isCloseTo(841.8898f, within(0.1f));
            BufferedImage image = new PDFRenderer(document).renderImageWithDPI(0, 300);
            int x = millimetresToPixels(49, 300);
            int y = millimetresToPixels(12, 300);
            int side = millimetresToPixels(21, 300);
            BufferedImage qr = image.getSubimage(x, y, side, side);
            String decoded = new MultiFormatReader()
                    .decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(qr))))
                    .getText();
            assertThat(decoded).isEqualTo("7K3MXY");
        }
    }

    @Test
    void paginatesAfterTwentyFourPositionsAndHonorsSkipPosition() throws Exception {
        AssetLabelEntry entry = new AssetLabelEntry("7K3MXY", "Cable", null, 1, "Cables", "#112233");
        byte[] bytes = AssetLabelDocument.labels(
                java.util.Collections.nCopies(24, entry), AssetLabelFormat.A4_70X36_24, 1, null);

        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(2);
        }
    }

    @Test
    void rejectsOverlappingOrOutOfBoundsCalibration() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> AssetLabelDocument.calibration(
                        AssetLabelFormat.A4_97X42_3_12, new AssetLabelCalibration(0f, 0f, 90f, 42.3f)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static int millimetresToPixels(float millimetres, float dpi) {
        return Math.round(millimetres / 25.4f * dpi);
    }

    private static org.assertj.core.data.Offset<Float> within(float value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
