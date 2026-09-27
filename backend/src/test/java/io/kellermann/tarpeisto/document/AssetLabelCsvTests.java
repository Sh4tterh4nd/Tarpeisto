package io.kellermann.tarpeisto.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssetLabelCsvTests {

    @Test
    void usesStableColumnsActualCrlfAndEscapesUnicodeQuotesCommasAndNewlines() {
        String csv = new String(
                AssetLabelCsv.write(List.of(new AssetLabelEntry(
                        "7K3MXY", "M\u00f6del, 1", "Name \"one\"\nnext", 4, "C\u00e2bl\u00e9s", "#AABBCC"))),
                StandardCharsets.UTF_8);

        assertThat(csv)
                .isEqualTo(
                        "model_name,asset_name,asset_code,category,category_color,qr_value\r\n"
                                + "\"M\u00f6del, 1\",\"Name \"\"one\"\"\nnext\",\"7K3MXY\",\"C\u00e2bl\u00e9s\",\"#AABBCC\",\"7K3MXY\"\r\n")
                .doesNotContain("\\r\\n");
    }
}
