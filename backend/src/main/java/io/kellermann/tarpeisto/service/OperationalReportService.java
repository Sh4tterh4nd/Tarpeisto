package io.kellermann.tarpeisto.service;

import io.kellermann.tarpeisto.document.OperationalReportCsv;
import io.kellermann.tarpeisto.exception.NotFoundException;
import io.kellermann.tarpeisto.model.ReportKind;
import io.kellermann.tarpeisto.repository.JdbcOperationalReportRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OperationalReportService {
    public record Artifact(Path path, String filename) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    private final JdbcOperationalReportRepository reports;
    private final java.time.Clock clock;

    public OperationalReportService(JdbcOperationalReportRepository reports, java.time.Clock clock) {
        this.reports = reports;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Artifact create(TarpeistoPrincipal p, ReportKind kind, UUID auditId) {
        if (p == null) throw new AccessDeniedException("Authentication required.");
        p.requirePermanent();
        if (auditId != null && !reports.auditExists(p.organizationId(), auditId))
            throw new NotFoundException("Audit not found.");
        var snapshotAt = clock.instant();
        Path path = null;
        try {
            path = Files.createTempFile("tarpeisto-report-", ".csv");
            try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                OperationalReportCsv.row(writer, reports.definition(kind).headers());
                String cursor = null;
                while (true) {
                    var page = reports.page(p.organizationId(), kind, auditId, cursor, 500, snapshotAt);
                    for (var row : page) OperationalReportCsv.row(writer, row.cells());
                    if (page.size() < 500) break;
                    cursor = page.getLast().cursor();
                }
            }
            return new Artifact(
                    path, kind.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-') + ".csv");
        } catch (IOException | RuntimeException failure) {
            if (path != null)
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            throw new IllegalStateException("Report could not be generated.", failure);
        }
    }
}
