package io.kellermann.tarpeisto.document;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Stable UTF-8 P-touch CSV contract, deliberately independent of a printer-vendor SDK. */
public final class AssetLabelCsv {

    private AssetLabelCsv() {}

    public static byte[] write(List<AssetLabelEntry> entries) {
        StringBuilder csv = new StringBuilder("model_name,asset_name,asset_code,category,category_color,qr_value\r\n");
        for (AssetLabelEntry entry : entries) {
            appendRow(
                    csv,
                    entry.modelName(),
                    entry.individualName() == null || entry.individualName().isBlank()
                            ? entry.modelName() + " " + entry.unitNumber()
                            : entry.individualName(),
                    entry.publicCode(),
                    entry.categoryName(),
                    entry.categoryColor(),
                    entry.publicCode());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendRow(StringBuilder csv, String... fields) {
        for (int index = 0; index < fields.length; index++) {
            if (index > 0) {
                csv.append(',');
            }
            String value = fields[index] == null ? "" : fields[index];
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }
}
