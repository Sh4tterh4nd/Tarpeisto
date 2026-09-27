package io.kellermann.tarpeisto.controller;

import io.kellermann.tarpeisto.security.TarpeistoPrincipal;
import io.kellermann.tarpeisto.service.PackingSheetService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Download endpoint for a direct-container packing sheet. */
@RestController
@RequestMapping("/api/v1/assets")
public class PackingSheetController {

    private final PackingSheetService packingSheetService;

    public PackingSheetController(PackingSheetService packingSheetService) {
        this.packingSheetService = packingSheetService;
    }

    @GetMapping(value = "/{assetId}/packing-sheet.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(operationId = "packingSheetPdf")
    public ResponseEntity<byte[]> pdf(
            @AuthenticationPrincipal TarpeistoPrincipal principal, @PathVariable UUID assetId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("packing-sheet-" + assetId + ".pdf")
                                .build()
                                .toString())
                .body(packingSheetService.pdf(principal, assetId));
    }
}
