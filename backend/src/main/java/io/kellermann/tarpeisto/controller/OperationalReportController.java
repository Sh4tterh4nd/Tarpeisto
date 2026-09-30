package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.model.ReportKind;
import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.OperationalReportService;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalReportController {
    private final OperationalReportService reports;

    public OperationalReportController(OperationalReportService reports) {
        this.reports = reports;
    }

    @ApiResponse(
            responseCode = "200",
            description = "UTF-8 CSV report download",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/api/v1/reports/inventory.csv", produces = "text/csv")
    public void inventory(@AuthenticationPrincipal TarpeistoPrincipal p, HttpServletResponse response)
            throws IOException {
        download(p, ReportKind.INVENTORY, null, response);
    }

    @ApiResponse(
            responseCode = "200",
            description = "UTF-8 CSV report download",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/api/v1/reports/consumable-balances.csv", produces = "text/csv")
    public void balances(@AuthenticationPrincipal TarpeistoPrincipal p, HttpServletResponse response)
            throws IOException {
        download(p, ReportKind.CONSUMABLE_BALANCES, null, response);
    }

    @ApiResponse(
            responseCode = "200",
            description = "UTF-8 CSV report download",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/api/v1/reports/stock-movements.csv", produces = "text/csv")
    public void movements(@AuthenticationPrincipal TarpeistoPrincipal p, HttpServletResponse response)
            throws IOException {
        download(p, ReportKind.STOCK_MOVEMENTS, null, response);
    }

    @ApiResponse(
            responseCode = "200",
            description = "UTF-8 CSV report download",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/api/v1/reports/audits.csv", produces = "text/csv")
    public void audits(
            @AuthenticationPrincipal TarpeistoPrincipal p,
            @RequestParam(required = false) UUID auditId,
            HttpServletResponse response)
            throws IOException {
        download(p, ReportKind.AUDITS, auditId, response);
    }

    @ApiResponse(
            responseCode = "200",
            description = "UTF-8 CSV report download",
            content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
    @GetMapping(value = "/api/v1/reports/audits/{auditId}.csv", produces = "text/csv")
    public void audit(
            @AuthenticationPrincipal TarpeistoPrincipal p, @PathVariable UUID auditId, HttpServletResponse response)
            throws IOException {
        download(p, ReportKind.AUDITS, auditId, response);
    }

    private void download(TarpeistoPrincipal p, ReportKind kind, UUID audit, HttpServletResponse response)
            throws IOException {
        // Generation finishes its repeatable-read transaction before any network streaming.
        // Synchronous ownership also cleans the artifact if header/output acquisition fails.
        try (var artifact = reports.create(p, kind, audit)) {
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifact.filename() + "\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            try (var input = Files.newInputStream(artifact.path())) {
                input.transferTo(response.getOutputStream());
            }
        }
    }
}
