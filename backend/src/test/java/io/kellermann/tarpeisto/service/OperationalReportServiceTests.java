package io.kellermann.tarpeisto.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.kellermann.tarpeisto.controller.OperationalReportController;
import io.kellermann.tarpeisto.model.OrganizationRole;
import io.kellermann.tarpeisto.model.ReportKind;
import io.kellermann.tarpeisto.repository.JdbcOperationalReportRepository;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.security.TemporaryAccessContext;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;

class OperationalReportServiceTests {
    private final JdbcOperationalReportRepository repository = mock(JdbcOperationalReportRepository.class);
    private final OperationalReportService service =
            new OperationalReportService(repository, java.time.Clock.fixed(Instant.EPOCH, java.time.ZoneOffset.UTC));
    private final TarpeistoPrincipal principal = new TarpeistoPrincipal(
            UUID.randomUUID(), "report-user", "Report user", UUID.randomUUID(), OrganizationRole.VIEWER);

    @ParameterizedTest
    @EnumSource(ReportKind.class)
    void everyReportContinuesBeyondItsPageAndEscapesOnlyText(ReportKind kind) throws Exception {
        when(repository.definition(kind))
                .thenReturn(new JdbcOperationalReportRepository.Definition(List.of("text", "delta", "at"), "", ""));
        var first = IntStream.range(0, 500)
                .mapToObj(i -> new JdbcOperationalReportRepository.Row(
                        "row-" + i,
                        List.<Object>of(
                                "=\u0394 \"quote\"\nnext",
                                new BigDecimal("-1.125"),
                                OffsetDateTime.parse("2026-09-30T12:00:00+03:00"))))
                .toList();
        when(repository.page(principal.organizationId(), kind, null, null, 500, Instant.EPOCH))
                .thenReturn(first);
        when(repository.page(principal.organizationId(), kind, null, "row-499", 500, Instant.EPOCH))
                .thenReturn(List.of(new JdbcOperationalReportRepository.Row(
                        "last", List.of("final", BigDecimal.ZERO, OffsetDateTime.parse("2026-09-30T09:00:00Z")))));
        Path path;
        try (var artifact = service.create(principal, kind, null)) {
            path = artifact.path();
            String csv = Files.readString(path);
            assertThat(csv)
                    .startsWith("\"text\",\"delta\",\"at\"\r\n")
                    .contains("\"'=\u0394 \"\"quote\"\"\nnext\",\"-1.125\",\"2026-09-30T09:00:00Z\"")
                    .endsWith("\"final\",\"0\",\"2026-09-30T09:00:00Z\"\r\n");
            assertThat(csv.split("\r\n")).hasSize(502);
        }
        assertThat(path).doesNotExist();
    }

    @Test
    void failedGenerationDeletesTheCreatedArtifact() throws Exception {
        when(repository.definition(ReportKind.INVENTORY))
                .thenReturn(new JdbcOperationalReportRepository.Definition(List.of("id"), "", ""));
        final Path[] created = {null};
        when(repository.page(principal.organizationId(), ReportKind.INVENTORY, null, null, 500, Instant.EPOCH))
                .thenAnswer(call -> {
                    try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
                        created[0] = files.filter(
                                        p -> p.getFileName().toString().startsWith("tarpeisto-report-"))
                                .max(java.util.Comparator.comparingLong(
                                        p -> p.toFile().lastModified()))
                                .orElseThrow();
                    }
                    throw new IllegalStateException("query failure");
                });
        assertThatThrownBy(() -> service.create(principal, ReportKind.INVENTORY, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be generated");
        assertThat(created[0]).doesNotExist();
    }

    @Test
    void outputAcquisitionFailureStillDeletesArtifact() throws Exception {
        var reportService = mock(OperationalReportService.class);
        var artifact = new OperationalReportService.Artifact(
                Files.createTempFile("tarpeisto-report-test-", ".csv"), "inventory.csv");
        when(reportService.create(principal, ReportKind.INVENTORY, null)).thenReturn(artifact);
        var response = mock(HttpServletResponse.class);
        when(response.getOutputStream()).thenThrow(new IOException("client disconnected"));
        assertThatThrownBy(() -> new OperationalReportController(reportService).inventory(principal, response))
                .isInstanceOf(IOException.class);
        assertThat(artifact.path()).doesNotExist();
    }

    @Test
    void temporaryIdentityIsRejectedBeforeAnyReportRead() {
        var temporary = new TarpeistoPrincipal(
                principal.userId(),
                principal.username(),
                principal.displayName(),
                principal.organizationId(),
                null,
                new TemporaryAccessContext(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        null,
                        UUID.randomUUID(),
                        Instant.now().plusSeconds(60)));
        assertThatThrownBy(() -> service.create(temporary, ReportKind.INVENTORY, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(repository);
    }
}
