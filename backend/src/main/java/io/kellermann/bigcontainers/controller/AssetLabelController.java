package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.document.AssetLabelCalibration;
import io.kellermann.bigcontainers.security.BigContainersPrincipal;
import io.kellermann.bigcontainers.service.AssetLabelService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authorized label and P-touch document downloads for organization-owned physical assets. */
@RestController
@RequestMapping("/api/v1/asset-labels")
public class AssetLabelController {

    private final AssetLabelService assetLabelService;

    public AssetLabelController(AssetLabelService assetLabelService) {
        this.assetLabelService = assetLabelService;
    }

    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @Valid @RequestBody CreateAssetLabelPdfRequest request) {
        return attachment(
                assetLabelService.labels(
                        principal,
                        request.assetIds(),
                        request.format(),
                        request.skipFirstPositions(),
                        calibration(request.calibration())),
                MediaType.APPLICATION_PDF,
                "bigcontainers-asset-labels.pdf");
    }

    @PostMapping(value = "/calibration", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> calibration(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @Valid @RequestBody CreateAssetLabelCalibrationRequest request) {
        return attachment(
                assetLabelService.calibration(principal, request.format(), calibration(request.calibration())),
                MediaType.APPLICATION_PDF,
                "bigcontainers-label-calibration.pdf");
    }

    @PostMapping(value = "/ptouch-csv", produces = "text/csv")
    public ResponseEntity<byte[]> ptouchCsv(
            @AuthenticationPrincipal BigContainersPrincipal principal,
            @Valid @RequestBody CreatePtouchCsvRequest request) {
        return attachment(
                assetLabelService.ptouchCsv(principal, request.assetIds()),
                MediaType.parseMediaType("text/csv;charset=UTF-8"),
                "bigcontainers-ptouch-labels.csv");
    }

    private static AssetLabelCalibration calibration(AssetLabelCalibrationParameters request) {
        return request == null
                ? null
                : new AssetLabelCalibration(
                        request.marginLeftMm(),
                        request.marginTopMm(),
                        request.horizontalPitchMm(),
                        request.verticalPitchMm());
    }

    private static ResponseEntity<byte[]> attachment(byte[] bytes, MediaType mediaType, String filename) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename)
                                .build()
                                .toString())
                .body(bytes);
    }
}
