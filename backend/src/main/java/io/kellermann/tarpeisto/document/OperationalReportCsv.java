package io.kellermann.tarpeisto.document;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** The report boundary protects text cells while retaining signed decimal cells as numbers. */
public final class OperationalReportCsv {
    private OperationalReportCsv() {}

    public static void row(Writer writer, List<?> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) writer.write(',');
            Object value = values.get(i);
            String text = value == null
                    ? ""
                    : value instanceof BigDecimal decimal
                            ? decimal.toPlainString()
                            : value instanceof OffsetDateTime at
                                    ? DateTimeFormatter.ISO_INSTANT.format(at.toInstant())
                                    : value.toString();
            if (value instanceof CharSequence) {
                String leading = text.stripLeading();
                if (!leading.isEmpty() && "=+-@".indexOf(leading.charAt(0)) >= 0
                        || text.startsWith("\t")
                        || text.startsWith("\r")) text = "'" + text;
            }
            writer.write('"');
            writer.write(text.replace("\"", "\"\""));
            writer.write('"');
        }
        writer.write("\r\n");
    }
}
