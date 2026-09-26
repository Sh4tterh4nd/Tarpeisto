package io.kellermann.bigcontainers.controller;

import io.kellermann.bigcontainers.service.AuditScanView;
import java.time.Instant;
import java.util.UUID;

public record AuditScanResponse(
        UUID id,
        UUID assetId,
        String assetCode,
        String outcome,
        Instant scannedAt,
        boolean undone,
        String contextSnapshot) {
    static AuditScanResponse from(AuditScanView v) {
        return new AuditScanResponse(
                v.id(), v.assetId(), v.assetCode(), v.outcome(), v.scannedAt(), v.undone(), v.contextSnapshot());
    }
}
