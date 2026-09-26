package io.kellermann.bigcontainers.document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/** Deterministic A4 checkout-manifest renderer using only immutable caller-supplied values. */
public final class CheckoutManifestDocument {
    private static final String FONT = "/document-fonts/RobotoMono-Regular.ttf";

    private CheckoutManifestDocument() {}

    public static byte[] render(List<String> lines) {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDType0Font font = load(document);
            PDPage page = null;
            PDPageContentStream content = null;
            float y = 800;
            for (String source : lines) {
                for (String line : wrap(source, 100)) {
                    if (content == null || y < 42) {
                        if (content != null) content.close();
                        page = new PDPage(PDRectangle.A4);
                        document.addPage(page);
                        content = new PDPageContentStream(document, page);
                        y = 800;
                    }
                    content.beginText();
                    content.setFont(font, 8);
                    content.newLineAtOffset(40, y);
                    content.showText(line);
                    content.endText();
                    y -= 11;
                }
            }
            if (content != null) content.close();
            document.save(output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create checkout manifest PDF.", e);
        }
    }

    /** Preferred typed input shape for checkout services; it cannot read mutable inventory. */
    public static byte[] render(Manifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        List<String> lines = new ArrayList<>();
        lines.add("BIGCONTAINERS CHECKOUT MANIFEST");
        lines.add("Event: " + display(manifest.eventName()));
        lines.add("Client: " + display(manifest.clientText()));
        lines.add("Venue: " + display(manifest.venueText()));
        lines.add("Schedule: " + display(manifest.startsAt()) + " - " + display(manifest.endsAt()));
        lines.add("Checked out: " + display(manifest.checkedOutAt()) + " by " + display(manifest.actorUserId()));
        lines.add("ASSETS");
        if (manifest.assets().isEmpty()) lines.add("(none)");
        for (AssetLine asset : manifest.assets()) {
            lines.add("[" + display(asset.publicCode()) + "] " + display(asset.modelName()) + " "
                    + display(asset.individualName()) + " | context: " + display(asset.containerContext()));
        }
        lines.add("CONSUMABLES");
        if (manifest.consumables().isEmpty()) lines.add("(none)");
        for (ConsumableLine consumable : manifest.consumables()) {
            lines.add(display(consumable.quantity()) + " " + display(consumable.unit()) + " "
                    + display(consumable.modelName()) + " | source: " + display(consumable.source()) + " | "
                    + display(consumable.semantics()));
        }
        if (!manifest.overrides().isEmpty()) {
            lines.add("OVERRIDES");
            manifest.overrides().forEach(override -> lines.add(display(override)));
        }
        return render(lines);
    }

    private static PDType0Font load(PDDocument document) throws IOException {
        try (InputStream stream = CheckoutManifestDocument.class.getResourceAsStream(FONT)) {
            if (stream == null) throw new IllegalStateException("Bundled checkout font is missing.");
            return PDType0Font.load(document, stream, true);
        }
    }

    private static List<String> wrap(String input, int width) {
        String text = input == null ? "-" : input.replaceAll("\\s+", " ").trim();
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        while (text.length() > width) {
            result.add(text.substring(0, width));
            text = text.substring(width);
        }
        result.add(text);
        return result;
    }

    private static String display(Object value) {
        return value == null || value.toString().isBlank() ? "-" : value.toString();
    }

    public record Manifest(
            String eventName,
            String clientText,
            String venueText,
            Instant startsAt,
            Instant endsAt,
            UUID actorUserId,
            Instant checkedOutAt,
            List<AssetLine> assets,
            List<ConsumableLine> consumables,
            List<String> overrides) {
        public Manifest {
            assets = List.copyOf(assets == null ? List.of() : assets);
            consumables = List.copyOf(consumables == null ? List.of() : consumables);
            overrides = List.copyOf(overrides == null ? List.of() : overrides);
        }
    }

    public record AssetLine(String publicCode, String modelName, String individualName, String containerContext) {}

    public record ConsumableLine(String modelName, String quantity, String unit, String source, String semantics) {}
}
