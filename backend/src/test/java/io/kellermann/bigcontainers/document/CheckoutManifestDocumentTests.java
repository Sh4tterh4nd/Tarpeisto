package io.kellermann.bigcontainers.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class CheckoutManifestDocumentTests {

    @Test
    void rendersEveryFrozenCheckoutFact() throws Exception {
        byte[] bytes = CheckoutManifestDocument.render(new CheckoutManifestDocument.Manifest(
                "Winter Festival",
                "Example client",
                "Main Stage",
                Instant.parse("2026-10-01T08:00:00Z"),
                Instant.parse("2026-10-01T21:00:00Z"),
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                Instant.parse("2026-09-30T10:15:00Z"),
                List.of(new CheckoutManifestDocument.AssetLine("7K3MXY", "Network Box", "Box 2", "Pallet A")),
                List.of(new CheckoutManifestDocument.ConsumableLine(
                        "Gaffer tape", "2.000", "roll", "Shelf 4", "SEPARATELY_ISSUED")),
                List.of("Deputy approved incomplete seal check.")));

        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(text).contains("Winter Festival", "7K3MXY", "Gaffer tape", "SEPARATELY_ISSUED", "OVERRIDES");
        }
    }

    @Test
    void paginatesWithinOneWrappedLineWithoutDroppingItsTail() throws Exception {
        String longLine = "asset ".repeat(1_500) + "TAIL-MARKER";

        byte[] bytes = CheckoutManifestDocument.render(List.of(longLine));

        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            assertThat(new PDFTextStripper().getText(document)).contains("TAIL-MARKER");
        }
    }
}
